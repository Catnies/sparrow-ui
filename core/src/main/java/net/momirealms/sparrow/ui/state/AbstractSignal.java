package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Observer;
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

/**
 * Signal 抽象实现骨架.
 *
 * @param <T> 值类型
 */
abstract sealed class AbstractSignal<T> implements Signal<T> permits
        MutableSignalImpl,
        MappedSignal,
        MapDistinctSignal,
        CombinedSignal,
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

    @Override
    @NotNull
    public Subscription subscribe(@NotNull Observer<? super T> observer) {
        Objects.requireNonNull(observer, "observer");
        return this.register(new ValueEntry(observer));
    }

    @Override
    @NotNull
    public Subscription onDirty(@NotNull Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        return this.register(new InvalidationEntry(listener));
    }

    @Override
    @NotNull
    public Subscription onDirtyWeak(@NotNull Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        // 弱引用的目标必须是新建的节点, 因为无捕获 lambda 与静态方法引用会被 JVM 缓存成常驻单例, 弱引用永远不会清空.
        BindingNode node = new BindingNode(listener);
        WeakNodeEntry entry = new WeakNodeEntry(node);
        node.bindEntry(entry);
        this.register(entry);
        return node;
    }

    @NotNull
    private Subscription register(Entry entry) {
        synchronized (this.activationLock) {
            // 已终止的信号不再接受订阅, 返回一条立即失效的凭证.
            if (this.retired) {
                entry.close();
                return entry;
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
        return entry;
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

    // 值与版本的原子快照对.
    record Versioned<V>(V value, long version) {
    }

    // 订阅条目.
    private abstract class Entry implements Subscription {
        private final AtomicBoolean closed = new AtomicBoolean();

        // 返回 {@code false} 表示宿主已被 GC, 条目应被剔除.
        abstract boolean deliver();

        @Override
        public boolean isClosed() {
            return this.closed.get();
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                AbstractSignal.this.unregister(this);
                this.onClosed();
            }
        }

        // 条目真正关闭后执行一次, 用于反向通知持有方.
        void onClosed() {
        }
    }

    private final class ValueEntry extends Entry {
        private final Observer<? super T> observer;

        private ValueEntry(Observer<? super T> observer) {
            this.observer = observer;
        }

        @Override
        boolean deliver() {
            this.observer.onUpdate(AbstractSignal.this.get());
            return true;
        }
    }

    private final class InvalidationEntry extends Entry {
        private final Runnable listener;

        private InvalidationEntry(Runnable listener) {
            this.listener = listener;
        }

        @Override
        boolean deliver() {
            this.listener.run();
            return true;
        }
    }

    private final class WeakNodeEntry extends Entry {
        private final NodeReference node;

        private WeakNodeEntry(BindingNode node) {
            this.node = new NodeReference(node, this, AbstractSignal.this.deadNodes);
        }

        @Override
        boolean deliver() {
            @Nullable Runnable target = this.node.get();
            if (target == null) {
                return false;
            }
            target.run();
            return true;
        }

        @Override
        void onClosed() {
            // 条目也可能被 retire 或死条目清理直接关掉, 那些路径不经过 BindingNode.close(),
            // 所以拆除统一收在这里: 节点还活着就让它丢掉回调与对本 signal 的引用.
            if (this.node.get() instanceof BindingNode node) {
                node.detach();
            }
        }
    }

    /**
     * 弱订阅的凭证, 同时是用户回调的宿主.
     * <p>本 signal 只弱引用它, 所以订阅的存活完全由持有本节点的一方决定. 它强持有条目,
     * 而条目是本 signal 的内部类, 因此持有本节点等于持有整条上游.
     * <p>关闭后 {@link #detach()} 会同时丢掉回调与条目, 于是回调捕获的对象和整条上游都当场可回收.
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
     * 携带所属条目, 节点被回收后可以直接从引用队列定位并关闭该条目.
     */
    private static final class NodeReference extends WeakReference<Runnable> {
        private final Subscription entry;

        private NodeReference(Runnable node, Subscription entry, ReferenceQueue<? super Runnable> queue) {
            super(node, queue);
            this.entry = entry;
        }
    }
}
