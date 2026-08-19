package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * {@link PlayerKeyedSignal} 的实现, 委托通用 KeyedSignal.
 * <p>创建时登记到 {@link PlayerSignalRegistry}, 由它在玩家退出时清除分区.
 *
 * @param <T> 值类型
 */
sealed class PlayerKeyedSignalImpl<T> implements PlayerKeyedSignal<T> permits MutablePlayerKeyedSignalImpl {
    private final KeyedSignal<UUID, T> delegate;

    PlayerKeyedSignalImpl(KeyedSignal<UUID, T> delegate) {
        this.delegate = delegate;
        PlayerSignalRegistry.track(delegate);
    }

    @Override
    public T get(@NotNull UUID key) {
        return this.delegate.get(key);
    }

    @Override
    public void dirty(@NotNull UUID key) {
        this.delegate.dirty(key);
    }

    @Override
    public void dirtyAll() {
        this.delegate.dirtyAll();
    }

    @Override
    @NotNull
    public Signal<T> at(@NotNull UUID key) {
        return this.delegate.at(key);
    }

    @Override
    public void remove(@NotNull UUID key) {
        this.delegate.remove(key);
    }

    @Override
    public void clear() {
        this.delegate.clear();
    }
}
