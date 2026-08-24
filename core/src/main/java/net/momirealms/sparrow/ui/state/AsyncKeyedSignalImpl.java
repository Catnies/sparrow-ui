package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * {@link KeyedSignal} 的异步实现.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 */
sealed class AsyncKeyedSignalImpl<K, T> extends AbstractKeyedSignal<K, T, AsyncSignalImpl<T>> permits PlayerKeyedSignalImpl {
    private final T placeholder;
    private final Executor executor;
    private final Function<? super K, ? extends T> loader;
    private final BiPredicate<? super T, ? super T> sameValue;
    @Nullable private final AsyncSignalImpl.Polling polling;    // 全部分区共用, 每个分区只在自己有订阅时轮询

    AsyncKeyedSignalImpl(T placeholder, Executor executor, Function<? super K, ? extends T> loader, BiPredicate<? super T, ? super T> sameValue, @Nullable AsyncSignalImpl.Polling polling) {
        this.placeholder = placeholder;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.sameValue = Objects.requireNonNull(sameValue, "sameValue");
        this.polling = polling;
    }

    @Override
    AsyncSignalImpl<T> createPartition(K key) {
        return new AsyncSignalImpl<>(this.placeholder, this.executor, () -> this.loader.apply(key), this.sameValue, this.polling);
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
