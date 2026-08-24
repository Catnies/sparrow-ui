package net.momirealms.sparrow.ui.state;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 可写的玩家分区响应式数据源.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public sealed interface MutablePlayerKeyedSignal<T> extends PlayerKeyedSignal<T>, MutableKeyedSignal<UUID, T> permits MutablePlayerKeyedSignalImpl {

    @Override
    @NotNull
    default MutableSignal<T> at(@NotNull Player player) {
        return this.at(player.getUniqueId());
    }

    default void set(@NotNull Player player, T value) {
        this.set(player.getUniqueId(), value);
    }

    default void update(@NotNull Player player, @NotNull UnaryOperator<T> updater) {
        this.update(player.getUniqueId(), updater);
    }
}
