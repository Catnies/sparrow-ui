package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * {@link MutablePlayerKeyedSignal} 的实现, 委托同步 KeyedSignal.
 *
 * @param <T> 值类型
 */
final class MutablePlayerKeyedSignalImpl<T> extends PlayerKeyedSignalImpl<T> implements MutablePlayerKeyedSignal<T> {
    private final MutableKeyedSignal<UUID, T> delegate;

    MutablePlayerKeyedSignalImpl(MutableKeyedSignal<UUID, T> delegate) {
        super(delegate);
        this.delegate = delegate;
    }

    @Override
    public void set(@NotNull UUID key, T value) {
        this.delegate.set(key, value);
    }

    @Override
    public void update(@NotNull UUID key, @NotNull UnaryOperator<T> updater) {
        this.delegate.update(key, updater);
    }
}
