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
import java.util.function.Consumer;
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
    private final ReferenceQueue<Object> deadOwners = new ReferenceQueue<>(); // 弱引用条目
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
    public <O> Subscription onDirtyWeak(@NotNull O owner, @NotNull Consumer<? super O> listener) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(listener, "listener");
        return this.register(new WeakInvalidationEntry<>(owner, listener));
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

    // 关闭弱宿主已被回收的条目.
    final void reapDeadEntries() {
        Reference<?> reference;
        while ((reference = this.deadOwners.poll()) != null) {
            if (reference instanceof OwnerReference<?> dead) {
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
            }
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

    private final class WeakInvalidationEntry<O> extends Entry {
        private final OwnerReference<O> owner;
        private final Consumer<? super O> listener;

        private WeakInvalidationEntry(O owner, Consumer<? super O> listener) {
            this.owner = new OwnerReference<>(owner, this, AbstractSignal.this.deadOwners);
            this.listener = listener;
        }

        @Override
        boolean deliver() {
            @Nullable O host = this.owner.get();
            if (host == null) {
                return false;
            }
            this.listener.accept(host);
            return true;
        }
    }

    /**
     * 弱宿主引用.
     * 携带所属条目, 宿主被回收后可以直接从引用队列定位并关闭该条目.
     */
    private static final class OwnerReference<O> extends WeakReference<O> {
        private final Subscription entry;

        private OwnerReference(O owner, Subscription entry, ReferenceQueue<? super O> queue) {
            super(owner, queue);
            this.entry = entry;
        }
    }
}
