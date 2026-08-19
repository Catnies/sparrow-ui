package net.momirealms.sparrow.ui.state;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * {@link KeyedSignal} 的异步实现.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 */
final class AsyncKeyedSignalImpl<K, T> extends AbstractKeyedSignal<K, T, AsyncSignalImpl<T>> {
    private final T placeholder;
    private final Executor executor;
    private final Function<? super K, ? extends T> loader;

    AsyncKeyedSignalImpl(T placeholder, Executor executor, Function<? super K, ? extends T> loader) {
        this(placeholder, executor, loader, KeyStateStore.generic());
    }

    AsyncKeyedSignalImpl(T placeholder, Executor executor, Function<? super K, ? extends T> loader, KeyStateStore<K, KeyState<K, T, AsyncSignalImpl<T>>> store) {
        super(store);
        this.placeholder = placeholder;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    AsyncSignalImpl<T> createPartition(K key) {
        return new AsyncSignalImpl<>(this.placeholder, this.executor, () -> this.loader.apply(key));
    }

    // 分区被取用时推动首载, 只有第一次真正生效.
    @Override
    void afterPartitionAccess(AsyncSignalImpl<T> partition) {
        partition.scheduleInitialLoad();
    }

    @Override
    void dirtyPartition(AsyncSignalImpl<T> partition) {
        partition.dirty();
    }
}
