package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * {@link MutableKeyedSignal} 的同步实现.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 */
final class KeyedSignalImpl<K, T> extends AbstractKeyedSignal<K, T, KeyedSignalImpl.SyncPartition<K, T>> implements MutableKeyedSignal<K, T> {
    private final Function<? super K, ? extends T> initial;
    private final BiPredicate<? super T, ? super T> sameValue;

    KeyedSignalImpl(Function<? super K, ? extends T> initial) {
        this(initial, AbstractSignal.defaultSameValue());
    }

    KeyedSignalImpl(Function<? super K, ? extends T> initial, BiPredicate<? super T, ? super T> sameValue) {
        this.initial = Objects.requireNonNull(initial, "initial");
        this.sameValue = Objects.requireNonNull(sameValue, "sameValue");
    }

    @Override
    SyncPartition<K, T> createPartition(K key) {
        return new SyncPartition<>(key, this.initial, this.sameValue);
    }

    @Override
    MutablePartitionHandle<K, T> createHandle(K key) {
        return new MutablePartitionHandle<>(this, key);
    }

    @Override
    @NotNull
    public MutablePartitionHandle<K, T> at(@NotNull K key) {
        return (MutablePartitionHandle<K, T>) super.at(key);
    }

    @Override
    void dirtyPartition(SyncPartition<K, T> partition) {
        partition.dirty();
    }

    @Override
    public void set(@NotNull K key, T value) {
        this.partition(key).set(value);
    }

    @Override
    public void update(@NotNull K key, @NotNull UnaryOperator<T> updater) {
        Objects.requireNonNull(updater, "updater");
        this.partition(key).update(updater);
    }

    /**
     * 同步分区实现, 携带 stale 标记 + 惰性重算的可写源节点.
     */
    static final class SyncPartition<K, T> extends AbstractSignal<T> {
        // 装载被并发失效连续打断时的重试上限, 触顶后返回最后一次装载结果并保持 stale.
        private static final int MAX_LOAD_ATTEMPTS = 8;

        private final K key;
        private final Function<? super K, ? extends T> initial;
        private final BiPredicate<? super T, ? super T> sameValue;
        private final AtomicReference<PartitionState<T>> state = new AtomicReference<>(new PartitionState<>(null, 0L, true));

        private SyncPartition(K key, Function<? super K, ? extends T> initial, BiPredicate<? super T, ? super T> sameValue) {
            this.key = key;
            this.initial = initial;
            this.sameValue = sameValue;
        }

        @Override
        public T get() {
            T value = null;
            // 提交失败说明期间已有其他状态变更, 进行一个有上限的重读重试.
            for (int attempt = 0; attempt < MAX_LOAD_ATTEMPTS; attempt++) {
                PartitionState<T> current = this.state.get();
                if (!current.stale()) {
                    return current.value();
                }
                value = this.initial.apply(this.key);
                if (this.state.compareAndSet(current, new PartitionState<>(value, current.version(), false))) {
                    return value;
                }
            }
            return value;
        }

        @Override
        long version() {
            return this.state.get().version();
        }

        void set(T value) {
            // 每一轮总有某个线程成功, 系统整体一定前进
            while (true) {
                PartitionState<T> current = this.state.get();
                if (!current.stale() && same(this.sameValue, current.value(), value)) {
                    return;
                }
                if (this.state.compareAndSet(current, new PartitionState<>(value, current.version() + 1, false))) {
                    this.notifyDirty();
                    return;
                }
            }
        }

        void update(UnaryOperator<T> updater) {
            // 每一轮总有某个线程成功, 系统整体一定前进
            while (true) {
                PartitionState<T> current = this.state.get();
                T base = current.stale() ? this.initial.apply(this.key) : current.value();
                T value = updater.apply(base);
                if (!current.stale() && same(this.sameValue, current.value(), value)) {
                    return;
                }
                if (this.state.compareAndSet(current, new PartitionState<>(value, current.version() + 1, false))) {
                    this.notifyDirty();
                    return;
                }
            }
        }

        // 无论当前是否已经 stale 都推进版本.
        void dirty() {
            // 每一轮总有某个线程成功, 系统整体一定前进
            while (true) {
                PartitionState<T> current = this.state.get();
                if (this.state.compareAndSet(current, new PartitionState<>(current.value(), current.version() + 1, true))) {
                    this.notifyDirty();
                    return;
                }
            }
        }

        private record PartitionState<V>(V value, long version, boolean stale) {
        }
    }
}