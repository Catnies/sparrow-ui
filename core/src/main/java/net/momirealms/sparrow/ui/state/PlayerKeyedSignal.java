package net.momirealms.sparrow.ui.state;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 以玩家为分区维度的 {@link KeyedSignal} 专属形态.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public interface PlayerKeyedSignal<T> extends KeyedSignal<UUID, T> {

    /**
     * 读取指定玩家分区的当前值.
     *
     * @param player 玩家
     * @return 分区当前快照值
     */
    default T get(@NotNull Player player) {
        return this.get(player.getUniqueId());
    }

    /**
     * 声明指定玩家分区的值已过期.
     *
     * @param player 玩家
     */
    default void dirty(@NotNull Player player) {
        this.dirty(player.getUniqueId());
    }

    /**
     * 返回指定玩家分区的 {@link Signal} 视图.
     *
     * @param player 玩家
     * @return 分区视图
     */
    @NotNull
    default Signal<T> at(@NotNull Player player) {
        return this.at(player.getUniqueId());
    }

    /**
     * 删除指定玩家的分区.
     *
     * @param player 玩家
     */
    default void remove(@NotNull Player player) {
        this.remove(player.getUniqueId());
    }

    /**
     * 创建一个同步的玩家分区数据源, 语义同 {@link KeyedSignal#of}, 外加退出自动删除.
     *
     * @param initial 分区装载与重算函数, 在读取线程执行, 必须线程安全且廉价非阻塞; 重量级装载请用 {@link #async}
     * @return 可写玩家分区 signal
     */
    @NotNull
    static <T> MutablePlayerKeyedSignal<T> of(@NotNull Function<? super UUID, ? extends T> initial) {
        return new MutablePlayerKeyedSignalImpl<>(KeyedSignal.of(initial));
    }

    /**
     * 创建一个异步的玩家分区数据源, 语义同 {@link KeyedSignal#async}, 外加退出自动删除.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行, 必须线程安全
     * @return 玩家分区 signal
     */
    @NotNull
    static <T> PlayerKeyedSignal<T> async(T placeholder, @NotNull Executor executor, @NotNull Function<? super UUID, ? extends T> loader) {
        return new PlayerKeyedSignalImpl<>(KeyedSignal.async(placeholder, executor, loader));
    }
}
