package net.momirealms.sparrow.ui.state;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * 以玩家 UUID 为分区维度的 {@link KeyedSignal}, 创建的数据源在玩家退出时自动删除分区.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public interface PlayerKeyedSignal<T> extends KeyedSignal<UUID, T> {

    default T get(@NotNull Player player) {
        return this.get(player.getUniqueId());
    }

    default void dirty(@NotNull Player player) {
        this.dirty(player.getUniqueId());
    }

    @NotNull
    default Signal<T> at(@NotNull Player player) {
        return this.at(player.getUniqueId());
    }

    default void remove(@NotNull Player player) {
        this.remove(player.getUniqueId());
    }

    /**
     * 创建一个同步的玩家分区数据源, 语义同 {@link KeyedSignal#of}, 外加退出自动删除.
     *
     * @param initial 分区装载与重算函数, 在读取线程执行.
     * @return 可写玩家分区 signal
     */
    @NotNull
    static <T> MutablePlayerKeyedSignal<T> of(@NotNull Function<? super UUID, ? extends T> initial) {
        return of(initial, AbstractSignal.defaultSameValue());
    }

    /**
     * 创建一个同步的玩家分区数据源并指定判等函数, 语义同 {@link KeyedSignal#of(Function, BiPredicate)}, 外加退出自动删除.
     *
     * @param initial 分区装载与重算函数, 在读取线程执行.
     * @param sameValue 判等函数, 语义见 {@link Signal#of(Object, BiPredicate)}
     * @return 可写玩家分区 signal
     */
    @NotNull
    static <T> MutablePlayerKeyedSignal<T> of(@NotNull Function<? super UUID, ? extends T> initial, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        return new MutablePlayerKeyedSignalImpl<>(new KeyedSignalImpl<>(initial, sameValue, KeyStateStore.generic()));
    }

    /**
     * 创建一个异步的玩家分区数据源, 语义同 {@link KeyedSignal#async}, 外加退出自动删除.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @return 玩家分区 signal
     */
    @NotNull
    static <T> PlayerKeyedSignal<T> async(T placeholder, @NotNull Executor executor, @NotNull Function<? super UUID, ? extends T> loader) {
        return async(placeholder, executor, loader, AbstractSignal.defaultSameValue());
    }

    /**
     * 创建一个异步的玩家分区数据源并指定判等函数, 语义同 {@link KeyedSignal#async(Object, Executor, Function, BiPredicate)}, 外加退出自动删除.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param sameValue 判等函数, 语义见 {@link Signal#of(Object, BiPredicate)}
     * @return 玩家分区 signal
     */
    @NotNull
    static <T> PlayerKeyedSignal<T> async(T placeholder, @NotNull Executor executor, @NotNull Function<? super UUID, ? extends T> loader, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        return new PlayerKeyedSignalImpl<>(new AsyncKeyedSignalImpl<>(placeholder, executor, loader, sameValue, KeyStateStore.generic(), null));
    }

    /**
     * 创建一个轮询的玩家分区数据源, 语义同 {@link KeyedSignal#polling(Object, Executor, Function, long)}, 外加退出自动删除.
     * <p>只有句柄被订阅了的玩家在轮询, 退出驱逐之后这名玩家的轮询随分区一起停.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param periodTicks 轮询周期, 必须为正
     * @return 轮询的玩家分区 signal
     * @throws IllegalArgumentException {@code periodTicks} 不是正数
     */
    @NotNull
    static <T> PlayerKeyedSignal<T> polling(T placeholder, @NotNull Executor executor, @NotNull Function<? super UUID, ? extends T> loader, long periodTicks) {
        return polling(placeholder, executor, loader, periodTicks, AbstractSignal.defaultSameValue());
    }

    /**
     * 同 {@link #polling(Object, Executor, Function, long)}, 但指定判等函数.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param periodTicks 轮询周期, 必须为正
     * @param sameValue 判等函数, 语义见 {@link Signal#of(Object, BiPredicate)}
     * @return 轮询的玩家分区 signal
     * @throws IllegalArgumentException {@code periodTicks} 不是正数
     */
    @NotNull
    static <T> PlayerKeyedSignal<T> polling(T placeholder, @NotNull Executor executor, @NotNull Function<? super UUID, ? extends T> loader, long periodTicks, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        return new PlayerKeyedSignalImpl<>(new AsyncKeyedSignalImpl<>(placeholder, executor, loader, sameValue, KeyStateStore.generic(), AsyncSignalImpl.Polling.everyTicks(periodTicks)));
    }

    /**
     * 同 {@link #polling(Object, Executor, Function, long)}, 但以毫秒计, 时钟挂在 Paper 异步调度器上.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param periodMillis 轮询周期毫秒数, 不小于 50
     * @return 轮询的玩家分区 signal
     * @throws IllegalArgumentException {@code periodMillis} 小于 50
     */
    @NotNull
    static <T> PlayerKeyedSignal<T> pollingMillis(T placeholder, @NotNull Executor executor, @NotNull Function<? super UUID, ? extends T> loader, long periodMillis) {
        return pollingMillis(placeholder, executor, loader, periodMillis, AbstractSignal.defaultSameValue());
    }

    /**
     * 同 {@link #pollingMillis(Object, Executor, Function, long)}, 但指定判等函数.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param periodMillis 轮询周期毫秒数, 不小于 50
     * @param sameValue 判等函数, 语义见 {@link Signal#of(Object, BiPredicate)}
     * @return 轮询的玩家分区 signal
     * @throws IllegalArgumentException {@code periodMillis} 小于 50
     */
    @NotNull
    static <T> PlayerKeyedSignal<T> pollingMillis(T placeholder, @NotNull Executor executor, @NotNull Function<? super UUID, ? extends T> loader, long periodMillis, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        return new PlayerKeyedSignalImpl<>(new AsyncKeyedSignalImpl<>(placeholder, executor, loader, sameValue, KeyStateStore.generic(), AsyncSignalImpl.Polling.everyMillis(periodMillis)));
    }
}
