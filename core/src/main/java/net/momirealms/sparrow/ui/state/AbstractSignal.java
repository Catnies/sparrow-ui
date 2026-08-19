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
        TickingSignal
{
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>(); // 订阅者
    private final ReferenceQueue<Runnable> deadNodes = new ReferenceQueue<>(); // 绑定节点已被回收的弱条目
    private final Object activationLock = new Object();
    private volatile boolean retired;

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
        return this.register(listener);
    }

    @NotNull
    private Subscription register(Runnable callback) {
        // 弱引用的目标必须是新建的节点, 因为无捕获 lambda 与静态方法引用会被 JVM 缓存成常驻单例, 弱引用永远不会清空.
        BindingNode node = new BindingNode(callback);
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

    // 关闭绑定节点已被回收的弱条目.
    final void reapDeadEntries() {
        Reference<?> reference;
        while ((reference = this.deadNodes.poll()) != null) {
            if (reference instanceof NodeReference dead) {
                dead.entry.close();
            }
        }
    }

    private void unregister(Entry entry) {
        synchronized (this.activationLock) {
            if (this.entries.remove(entry) && this.entries.isEmpty()) {
                this.onInactive();
            }
        }
    }

    // 向所有活的订阅派发一次失效.
    protected final void notifyDirty() {
        if (this.retired) return;
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
                entry.close();
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
        Objects.requireNonNull(mapper, "mapper");
        return new MapDistinctSignal<>(this, mapper);
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
        for (Entry entry : this.entries) {
            try {
                entry.close();
            } catch (RuntimeException exception) {
                SparrowUI.getInstance().handleException("Failed to close a signal subscription", exception);
            }
        }
    }

    // 把公开工厂收到的 signal 收窄到内部实现.
    static <T> AbstractSignal<T> require(Signal<T> signal) {
        return (AbstractSignal<T>) Objects.requireNonNull(signal, "signal");
    }

    /**
     * 给一批来源挂上同一个失效回调.
     *
     * @param sources 要挂的来源
     * @param listener 失效回调
     * @return 与 sources 同下标的订阅凭证
     */
    @NotNull
    static Subscription[] attachAll(AbstractSignal<?>[] sources, @NotNull Runnable listener) {
        Subscription[] subscriptions = new Subscription[sources.length];
        int attached = 0;
        try {
            for (int index = 0; index < sources.length; index++) {
                subscriptions[index] = sources[index].onDirty(listener);
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
            if (this.closed.compareAndSet(false, true)) {
                AbstractSignal.this.unregister(this);
                // 本条目也可能被 retire 或死条目清理直接关掉, 那些路径不经过 BindingNode.close(),
                // 所以拆除统一收在这里: 节点还活着就让它丢掉回调与对本 signal 的引用.
                if (this.node.get() instanceof BindingNode node) {
                    node.detach();
                }
            }
        }
    }

    /**
     * 弱订阅的凭证, 强持有用户回调.
     * <p>本 signal 只弱引用它, 所以订阅的存活完全由持有本节点的一方决定. 它强持有条目,
     * 而条目是本 signal 的内部类, 因此持有本节点等于持有整条上游.
     */
    private static final class BindingNode implements Subscription, Runnable {
        @Nullable private volatile Runnable callback;   // 关闭后置 null
        @Nullable private volatile Subscription entry;  // 注册后填入, 关闭后置 null
        private volatile boolean closed;

        private BindingNode(@NotNull Runnable callback) {
            this.callback = callback;
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
        }
    }

    /**
     * 对 {@link BindingNode} 的弱引用, 目标以 {@link Runnable} 形态存放.
     */
    private static final class NodeReference extends WeakReference<Runnable> {
        private final Subscription entry;

        private NodeReference(Runnable node, Subscription entry, ReferenceQueue<? super Runnable> queue) {
            super(node, queue);
            this.entry = entry;
        }
    }
}
