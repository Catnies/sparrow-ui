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
public interface MutablePlayerKeyedSignal<T> extends PlayerKeyedSignal<T>, MutableKeyedSignal<UUID, T> {

    /**
     * 写入指定玩家分区的新值.
     *
     * @param player 玩家
     * @param value 新值, 允许为 {@code null}
     */
    default void set(@NotNull Player player, T value) {
        this.set(player.getUniqueId(), value);
    }

    /**
     * 基于指定玩家分区的当前值原子更新.
     *
     * @param player 玩家
     * @param updater 纯函数
     */
    default void update(@NotNull Player player, @NotNull UnaryOperator<T> updater) {
        this.update(player.getUniqueId(), updater);
    }
}
