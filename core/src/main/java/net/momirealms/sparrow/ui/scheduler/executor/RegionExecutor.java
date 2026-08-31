package net.momirealms.sparrow.ui.scheduler.executor;

import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executor;

/**
 * 在全局线程或指定世界区域执行同步任务.
 *
 * @param <W> 世界类型
 */
public interface RegionExecutor<W> extends Executor {

    /**
     * 在指定区域执行任务, {@code world} 为 {@code null} 时使用全局同步上下文.
     *
     * @param runnable 要执行的任务
     * @param world 目标世界, {@code null} 表示全局上下文
     * @param x 目标区块 X 坐标
     * @param z 目标区块 Z 坐标
     */
    void run(@NotNull Runnable runnable, @Nullable W world, int x, int z);

    /**
     * 在全局同步上下文执行任务.
     *
     * @param runnable 要执行的任务
     */
    default void run(@NotNull Runnable runnable) {
        this.run(runnable, null, 0, 0);
    }

    /**
     * 将任务安排到后续同步调度周期.
     *
     * @param runnable 要执行的任务
     * @param world 目标世界, {@code null} 表示全局上下文
     * @param x 目标区块 X 坐标
     * @param z 目标区块 Z 坐标
     */
    void runDelayed(@NotNull Runnable runnable, @Nullable W world, int x, int z);

    /**
     * 将任务安排到后续全局同步调度周期.
     *
     * @param runnable 要执行的任务
     */
    default void runDelayed(@NotNull Runnable runnable) {
        this.runDelayed(runnable, null, 0, 0);
    }

    /**
     * 在指定区域延迟执行一次任务.
     *
     * @param runnable 要执行的任务
     * @param delay 延迟 tick 数
     * @param world 目标世界, {@code null} 表示全局上下文
     * @param x 目标区块 X 坐标
     * @param z 目标区块 Z 坐标
     * @return 可取消的调度任务
     */
    @NotNull
    SchedulerTask runLater(@NotNull Runnable runnable, long delay, @Nullable W world, int x, int z);

    /**
     * 在全局同步上下文延迟执行一次任务.
     *
     * @param runnable 要执行的任务
     * @param delay 延迟 tick 数
     * @return 可取消的调度任务
     */
    @NotNull
    default SchedulerTask runLater(@NotNull Runnable runnable, long delay) {
        return this.runLater(runnable, delay, null, 0, 0);
    }

    /**
     * 在指定区域重复执行任务.
     *
     * @param runnable 要执行的任务
     * @param delay 首次执行前的 tick 数
     * @param period 相邻执行之间的 tick 数
     * @param world 目标世界, {@code null} 表示全局上下文
     * @param x 目标区块 X 坐标
     * @param z 目标区块 Z 坐标
     * @return 可取消的调度任务
     */
    @NotNull
    SchedulerTask runRepeating(@NotNull Runnable runnable, long delay, long period, @Nullable W world, int x, int z);

    /**
     * 在全局同步上下文重复执行任务.
     *
     * @param runnable 要执行的任务
     * @param delay 首次执行前的 tick 数
     * @param period 相邻执行之间的 tick 数
     * @return 可取消的调度任务
     */
    @NotNull
    default SchedulerTask runRepeating(@NotNull Runnable runnable, long delay, long period) {
        return this.runRepeating(runnable, delay, period, null, 0, 0);
    }

    /**
     * 通过平台异步调度器重复执行任务.
     * 没有异步 tick 调度器的平台按每 tick 50ms 换算为墙钟时间.
     *
     * @param runnable 要执行的任务
     * @param delay 首次执行前的 tick 数
     * @param period 相邻执行之间的 tick 数
     * @return 可取消的调度任务
     */
    @NotNull
    SchedulerTask runAsyncRepeating(@NotNull Runnable runnable, long delay, long period);

    /**
     * 通过平台异步调度器延迟执行任务.
     * 没有异步 tick 调度器的平台按每 tick 50ms 换算为墙钟时间.
     *
     * @param runnable 要执行的任务
     * @param delay 延迟 tick 数
     * @return 可取消的调度任务
     */
    @NotNull
    SchedulerTask runAsyncLater(@NotNull Runnable runnable, long delay);
}
