package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Function;

abstract sealed class AbstractSignal<T> implements Signal<T> permits
        MutableSignalImpl,
        MappedSignal,
        MapDistinctSignal,
        CombinedSignal,
        SwitchingSignal,
        MergingSignal,
        AsyncSignalImpl,
        KeyedSignalImpl.SyncPartition,
        PartitionHandle,
        TickingSignal,
        PacedSignal,
        CountdownSignal,
        CollectionSignal,
        AbstractKeyedSignal.Keys
{
    private static final BiPredicate<Object, Object> DEFAULT_SAME_VALUE = Objects::equals;

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>(); // 订阅者
    private final ReferenceQueue<Runnable> deadNodes = new ReferenceQueue<>(); // 绑定节点已被回收的弱条目
    private final Object activationLock = new Object();
    private volatile boolean retired;
    // 正在派发本节点的线程. 只拿来跟本线程自己比, 若并发则有一定的概率在派发时标记会被别人盖掉, 那种情况下大半的重入抓不到, 只能靠某一层恰好撞上把递归拦下.
    @Nullable private Thread dispatchingThread;

    // 当前值版本, 值可能变化时单调递增.
    abstract long version();

    // 首个订阅到来时在 {@code activationLock} 内被调用.
    protected void onActive() {
    }

    // 最后一个订阅移除时在 {@code activationLock} 内被调用.
    protected void onInactive() {
    }

    @NotNull
    @Override
    public Subscription onDirty(@NotNull Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        return this.register(listener, null);
    }

    /**
     * 派生节点订阅上游用这个, 与 {@link #onDirty} 只差一点: 条目记下谁是下游, {@link #reapDownstream} 才能顺着订阅链走下去.
     *
     * @param downstream 订阅本节点的派生节点
     * @param listener 失效回调
     * @return 订阅凭证, 与 onDirty 的一样弱
     */
    @NotNull
    final Subscription link(@NotNull AbstractSignal<?> downstream, @NotNull Runnable listener) {
        return this.register(listener, downstream);
    }

    @NotNull
    private Subscription register(Runnable callback, @Nullable AbstractSignal<?> downstream) {
        // 弱引用的目标必须是新建的节点, 因为无捕获 lambda 与静态方法引用会被 JVM 缓存成常驻单例, 弱引用永远不会清空.
        BindingNode node = new BindingNode(callback, downstream);
        Entry entry = new Entry(node);
        node.bindEntry(entry);
        synchronized (this.activationLock) {
            // 已终止的信号不再接受订阅, 返回一条立即失效的凭证.
            if (this.retired) {
                entry.close();
                return node;
            }
            this.entries.add(entry);
            if (this.entries.size() == 1) {
                try {
                    this.onActive();
                } catch (RuntimeException | Error exception) {
                    this.entries.remove(entry);
                    throw exception;
                }
            }
        }
        this.reapDeadEntries();
        return node;
    }

    private void unregister(Entry entry) {
        synchronized (this.activationLock) {
            if (this.entries.remove(entry) && this.entries.isEmpty()) {
                this.onInactive();
            }
        }
    }

    // 关闭绑定节点已被回收的弱条目.
    // 派发路径不必调用, deliver() 本来就会就地剔除死条目, 会截断上游失效的节点则要在失效回调入口自己补一次.
    final void reapDeadEntries() {
        Reference<?> reference = this.deadNodes.poll();
        if (reference == null) return;
        // 一次菜单关闭会让同一个共享 signal 上的一整批条目同时死亡, 逐条摘表就要复制一遍订阅表, 所以整批先只标关闭, 摘表统一交给一次清扫.
        do {
            if (reference instanceof NodeReference dead) {
                dead.entry.markClosed();
            }
        } while ((reference = this.deadNodes.poll()) != null);
        this.sweepClosed();
    }

    // 先让下游各派生节点清一遍死条目, 再清自己, 全程不派发失效.
    // 装载结果判等不变的轮询源没有派发机会, 下游死光了也没人发现, 只能由它在时钟到拍时主动走一遍:
    // 派生节点清到空会自己退订, 退订逐级回到这里, 本节点随之清到空, onInactive 才有机会把时钟收掉.
    final void reapDownstream() {
        for (Entry entry : this.entries) {
            if (entry.isClosed()) continue;
            if (entry.node.get() instanceof BindingNode node) {
                AbstractSignal<?> downstream = node.downstream;
                if (downstream != null) downstream.reapDownstream();
            }
        }
        this.reapDeadEntries();
    }

    /**
     * 向所有活的订阅派发一次失效.
     *
     * @throws IllegalStateException 本线程正在派发本节点时再次调用, 也就是回调里又让这个 signal 失效了
     */
    protected final void notifyDirty() {
        if (this.retired || this.entries.isEmpty()) return;
        if (this.dispatchingThread == Thread.currentThread()) {
            throw new IllegalStateException("Reentrant invalidation: a listener invalidated this signal while it was still dispatching");
        }
        this.dispatchingThread = Thread.currentThread();
        try {
            boolean reap = false;
            for (Entry entry : this.entries) {
                if (entry.isClosed()) {
                    continue;
                }
                boolean alive = true;
                try {
                    alive = entry.deliver();
                } catch (RuntimeException exception) {
                    SparrowUI.getInstance().handleException("Failed to deliver a signal invalidation", exception);
                }
                if (!alive) {
                    // 本轮发现的死条目一起摘, 边派发边逐条摘表在订阅多的 signal 上是平方级
                    reap |= entry.markClosed();
                }
            }
            if (reap) this.sweepClosed();
        } finally {
            this.dispatchingThread = null;
        }
    }

    // 把已经标关闭的条目一次性摘掉, 有多少条都只复制一遍订阅表.
    private void sweepClosed() {
        synchronized (this.activationLock) {
            if (this.entries.removeIf(Entry::isClosed) && this.entries.isEmpty()) {
                this.onInactive();
            }
        }
    }

    @Override
    @NotNull
    public <R> Signal<R> map(@NotNull Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new MappedSignal<>(this, mapper);
    }

    @Override
    @NotNull
    public <R> Signal<R> mapDistinct(@NotNull Function<? super T, ? extends R> mapper) {
        return this.mapDistinct(mapper, defaultSameValue());
    }

    @Override
    @NotNull
    public <R> Signal<R> mapDistinct(@NotNull Function<? super T, ? extends R> mapper, @NotNull BiPredicate<? super R, ? super R> sameValue) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(sameValue, "sameValue");
        return new MapDistinctSignal<>(this, mapper, sameValue);
    }

    @Override
    @NotNull
    public Signal<T> debounce(long ticks) {
        if (ticks <= 0) throw new IllegalArgumentException("ticks must be positive: " + ticks);
        return new DebounceSignal<>(this, ticks, Signals.tickDelayer());
    }

    @Override
    @NotNull
    public Signal<T> debounceMillis(long millis) {
        if (millis <= 0) throw new IllegalArgumentException("millis must be positive: " + millis);
        return new DebounceSignal<>(this, millis, Signals.millisDelayer());
    }

    @Override
    @NotNull
    public Signal<T> throttle(long ticks) {
        if (ticks <= 0) throw new IllegalArgumentException("ticks must be positive: " + ticks);
        return new ThrottleSignal<>(this, ticks, Signals.tickDelayer());
    }

    @Override
    @NotNull
    public Signal<T> throttleMillis(long millis) {
        if (millis <= 0) throw new IllegalArgumentException("millis must be positive: " + millis);
        return new ThrottleSignal<>(this, millis, Signals.millisDelayer());
    }

    // 当前订阅条目数.
    final int entryCount() {
        return this.entries.size();
    }

    // 此数据源是否已终止.
    final boolean isRetired() {
        return this.retired;
    }

    // 终止此数据源, 关闭全部订阅, 之后不再接受新订阅也不再派发.
    final void retire() {
        synchronized (this.activationLock) {
            if (this.retired) return;
            this.retired = true;
        }
        // 同样整批先标关闭再一次清扫.
        for (Entry entry : this.entries) {
            entry.markClosed();
        }
        try {
            this.sweepClosed();
        } catch (RuntimeException exception) {
            SparrowUI.getInstance().handleException("Failed to close a signal subscription", exception);
        }
    }

    static BiPredicate<Object, Object> defaultSameValue() {
        return DEFAULT_SAME_VALUE;
    }

    // 判等函数的空值策略, 全部节点共用这一份. 两边都是 null 算相同, 只有一边是 null 算不同,
    // 两边都非 null 才轮得到判等函数, 所以 ItemStack::isSimilar 这类不接受 null 的方法引用可以直接传.
    static <V> boolean same(BiPredicate<? super V, ? super V> sameValue, V current, V candidate) {
        if (current == null || candidate == null) {
            return current == candidate;
        }
        return sameValue.test(current, candidate);
    }

    // 把公开工厂收到的 signal 收窄到内部实现.
    static <T> AbstractSignal<T> require(Signal<T> signal) {
        return (AbstractSignal<T>) Objects.requireNonNull(signal, "signal");
    }

    /**
     * 给一批来源挂上同一个失效回调.
     *
     * @param sources 要挂的来源
     * @param downstream 订阅这批来源的派生节点
     * @param listener 失效回调
     * @return 与 sources 同下标的订阅凭证
     */
    @NotNull
    static Subscription[] attachAll(AbstractSignal<?>[] sources, @NotNull AbstractSignal<?> downstream, @NotNull Runnable listener) {
        Subscription[] subscriptions = new Subscription[sources.length];
        int attached = 0;
        try {
            for (int index = 0; index < sources.length; index++) {
                subscriptions[index] = sources[index].link(downstream, listener);
                attached++;
            }
        } catch (RuntimeException | Error exception) {
            // 中途有谁激活失败就倒序撤销已经挂上的那些, 再把异常原样抛出
            for (int index = attached - 1; index >= 0; index--) {
                subscriptions[index].close();
            }
            throw exception;
        }
        return subscriptions;
    }

    /**
     * 关闭一批凭证, 为 {@code null} 时无操作.
     *
     * @param subscriptions 订阅凭证
     */
    static void closeAll(Subscription @Nullable [] subscriptions) {
        if (subscriptions == null) return;
        for (int index = 0; index < subscriptions.length; index++) {
            subscriptions[index].close();
        }
    }

    // 值与版本的原子快照对.
    record Versioned<V>(V value, long version) {
    }

    /**
     * 订阅条目, 本 signal 只弱引用绑定节点, 凭证一丢订阅就死亡.
     */
    private final class Entry implements Subscription {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final NodeReference node;

        private Entry(BindingNode node) {
            this.node = new NodeReference(node, this, AbstractSignal.this.deadNodes);
        }

        // 返回 {@code false} 表示节点已被 GC, 条目应被剔除.
        private boolean deliver() {
            @Nullable Runnable target = this.node.get();
            if (target == null) {
                return false;
            }
            target.run();
            return true;
        }

        @Override
        public boolean isClosed() {
            return this.closed.get();
        }

        @Override
        public void close() {
            if (this.markClosed()) {
                AbstractSignal.this.unregister(this);
            }
        }

        // 标记关闭并拆掉与绑定节点的相互引用, 但不动订阅表; 返回是否由本次调用完成关闭.
        private boolean markClosed() {
            if (!this.closed.compareAndSet(false, true)) {
                return false;
            }
            // 本条目也可能被 retire 或死条目清理直接关掉, 那些路径不经过 BindingNode.close(),
            // 所以拆除统一收在这里: 节点还活着就让它丢掉回调与对本 signal 的引用.
            if (this.node.get() instanceof BindingNode node) {
                node.detach();
            }
            return true;
        }
    }

    /**
     * 弱订阅的凭证, 强持有用户回调.
     * <p>本 signal 只弱引用它, 所以订阅的存活完全由持有本节点的一方决定. 它强持有条目,
     * 而条目是本 signal 的内部类, 因此持有本节点等于持有整条上游.
     */
    private static final class BindingNode implements Subscription, Runnable {
        @Nullable private volatile Runnable callback;               // 关闭后置 null
        @Nullable private volatile Subscription entry;              // 注册后填入, 关闭后置 null
        @Nullable private volatile AbstractSignal<?> downstream;    // 经 link 订阅的派生节点, 用户订阅为 null, 关闭后置 null
        private volatile boolean closed;

        private BindingNode(@NotNull Runnable callback, @Nullable AbstractSignal<?> downstream) {
            this.callback = callback;
            this.downstream = downstream;
        }

        private void bindEntry(Subscription entry) {
            this.entry = entry;
        }

        @Override
        public void run() {
            @Nullable Runnable target = this.callback;
            if (target != null) {
                target.run();
            }
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }

        @Override
        public void close() {
            @Nullable Subscription current = this.entry;
            if (current != null) {
                current.close();
            } else {
                this.detach();
            }
        }

        // 断开两个方向的引用, 不再持有用户回调, 也不再经条目持有所属 signal.
        private void detach() {
            this.closed = true;
            this.callback = null;
            this.entry = null;
            this.downstream = null;
        }
    }

    /**
     * 对 {@link BindingNode} 的弱引用, 目标以 {@link Runnable} 形态存放.
     */
    private static final class NodeReference extends WeakReference<Runnable> {
        private final AbstractSignal<?>.Entry entry;

        private NodeReference(Runnable node, AbstractSignal<?>.Entry entry, ReferenceQueue<? super Runnable> queue) {
            super(node, queue);
            this.entry = entry;
        }
    }
}