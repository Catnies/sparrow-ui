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
        CollectionSignal,
        AbstractKeyedSignal.Keys
{
    private static final BiPredicate<Object, Object> DEFAULT_SAME_VALUE = Objects::equals;

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final ReferenceQueue<Runnable> deadNodes = new ReferenceQueue<>(); // 已回收的绑定节点
    private final Object activationLock = new Object();
    private volatile boolean retired;
    // 只可靠拦截同线程重入. 并发派发会互相覆盖此标记, 跨线程反馈环仍受公开契约禁止, 检测只作尽力而为.
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
     * 将本节点接到上游. 订阅条目同时记录下游节点, 供 {@link #reapDownstream} 沿派生链清理死订阅.
     * <p>回调前先清理本节点, 让截断失效的节点也能发现订阅者已经全部离开.
     *
     * @param source 要订阅的上游
     * @param listener 失效回调
     * @return 弱订阅凭证
     */
    @NotNull
    final Subscription linkTo(@NotNull AbstractSignal<?> source, @NotNull Runnable listener) {
        // 截断或推迟失效的节点可能长期没有派发机会, 在上游失效入口补一次清理才能及时停掉整条空链.
        return source.register(() -> {
            this.reapDeadEntries();
            listener.run();
        }, this);
    }

    // 一次接上多个固定上游, 中途失败时撤销已经建立的订阅.
    @NotNull
    final Subscription[] linkAll(AbstractSignal<?>[] sources, @NotNull Runnable listener) {
        Subscription[] subscriptions = new Subscription[sources.length];
        int linked = 0;
        try {
            for (int index = 0; index < sources.length; index++) {
                subscriptions[index] = this.linkTo(sources[index], listener);
                linked++;
            }
        } catch (RuntimeException | Error exception) {
            // 倒序撤销已经挂上的来源, 再把激活异常原样抛出
            for (int index = linked - 1; index >= 0; index--) {
                subscriptions[index].close();
            }
            throw exception;
        }
        return subscriptions;
    }

    @NotNull
    private Subscription register(Runnable callback, @Nullable AbstractSignal<?> downstream) {
        // 弱引用指向每次新建的节点. JVM 可能缓存无捕获 lambda 与静态方法引用, 直接弱引用户回调会让订阅无法消亡.
        BindingNode node = new BindingNode(callback, downstream);
        Entry entry = new Entry(node);
        node.bindEntry(entry);
        synchronized (this.activationLock) {
            // 已终止的来源返回一条已经关闭的凭证
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

    // 清理绑定节点已被回收的弱条目. 派发路径会自行发现, 截断失效的节点需要在上游回调入口主动调用.
    final void reapDeadEntries() {
        Reference<?> reference = this.deadNodes.poll();
        if (reference == null) return;
        // 一次菜单关闭常会释放整批订阅, 先标记再批量摘表, 避免 CopyOnWriteArrayList 为每条死亡记录复制一次.
        do {
            if (reference instanceof NodeReference dead) {
                dead.entry.markClosed();
            }
        } while ((reference = this.deadNodes.poll()) != null);
        this.sweepClosed();
    }

    // 从下游向上清理整条派生链, 全程不发送失效. 轮询值长期不变时靠时钟入口调用, 空链才能逐级退订并停表.
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
                    // 本轮发现的死条目批量摘表, 避免派发期间反复复制订阅数组
                    reap |= entry.markClosed();
                }
            }
            if (reap) this.sweepClosed();
        } finally {
            this.dispatchingThread = null;
        }
    }

    // 一次摘掉全部已关闭条目
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

    final int entryCount() {
        return this.entries.size();
    }

    final boolean isRetired() {
        return this.retired;
    }

    // 终止来源并关闭全部订阅, 后续注册直接得到已关闭凭证
    final void retire() {
        synchronized (this.activationLock) {
            if (this.retired) return;
            this.retired = true;
        }
        // 整批标记后只清扫一次
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

    // 所有节点共用同一套空值判等. 两边都非 null 时才调用用户函数, ItemStack::isSimilar 一类方法引用可以直接传入.
    static <V> boolean same(BiPredicate<? super V, ? super V> sameValue, V current, V candidate) {
        if (current == null || candidate == null) {
            return current == candidate;
        }
        return sameValue.test(current, candidate);
    }

    static <T> AbstractSignal<T> require(Signal<T> signal) {
        return (AbstractSignal<T>) Objects.requireNonNull(signal, "signal");
    }

    // 关闭一批可能尚未建立的订阅
    static void closeAll(Subscription @Nullable [] subscriptions) {
        if (subscriptions == null) return;
        for (int index = 0; index < subscriptions.length; index++) {
            subscriptions[index].close();
        }
    }

    // 值与版本的原子快照对.
    record Versioned<V>(V value, long version) {
    }

    // 来源只经弱引用持有绑定节点, 凭证消亡后条目等待下一次清扫
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

        // 标记关闭并拆掉与绑定节点的相互引用, 订阅表留给批量清扫, 返回本次是否完成关闭
        private boolean markClosed() {
            if (!this.closed.compareAndSet(false, true)) {
                return false;
            }
            // 本条目也可能被 retire 或死条目清理直接关掉, 那些路径不经过 BindingNode.close(),
            // 所有关闭路径都在这里断开回调与来源引用
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
        @Nullable private volatile AbstractSignal<?> downstream;    // 经 linkTo 订阅的派生节点, 用户订阅为 null, 关闭后置 null
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

        // 断开两个方向的引用, 释放用户回调与所属 signal
        private void detach() {
            this.closed = true;
            this.callback = null;
            this.entry = null;
            this.downstream = null;
        }
    }

    private static final class NodeReference extends WeakReference<Runnable> {
        private final AbstractSignal<?>.Entry entry;

        private NodeReference(Runnable node, AbstractSignal<?>.Entry entry, ReferenceQueue<? super Runnable> queue) {
            super(node, queue);
            this.entry = entry;
        }
    }
}
