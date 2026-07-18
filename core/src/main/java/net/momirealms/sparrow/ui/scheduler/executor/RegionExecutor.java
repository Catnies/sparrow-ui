package net.momirealms.sparrow.ui.scheduler.executor;

import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;

import java.util.concurrent.Executor;

/**
 * 区域执行器接口.
 * 提供在指定世界和区块坐标的上下文中执行任务的能力,
 * 同时支持延迟执行和重复执行的调度功能.
 *
 * @param <W> 世界类型参数.
 */
public interface RegionExecutor<W> extends Executor {

    /**
     * 在指定世界和区块坐标的区域上下文中执行一个任务.
     *
     * @param runnable 需要执行的任务.
     * @param world    任务执行所在的世界, 可为 null.
     * @param x        区块的 X 坐标.
     * @param z        区块的 Z 坐标.
     */
    void run(Runnable runnable, W world, int x, int z);

    /**
     * 在默认区域上下文中执行一个任务 (不指定世界和区块坐标).
     *
     * @param runnable 需要执行的任务.
     */
    default void run(Runnable runnable) {
        run(runnable, null, 0, 0);
    }

    /**
     * 在指定世界和区块坐标的区域上下文中延迟执行一个任务.
     * 这个任务会被安排到本 Tick 末执行.
     *
     * @param runnable 需要执行的任务.
     * @param world    任务执行所在的世界, 可为 null.
     * @param x        区块的 X 坐标.
     * @param z        区块的 Z 坐标.
     */
    void runDelayed(Runnable runnable, W world, int x, int z);

    /**
     * 在默认区域上下文中延迟执行一个任务 (不指定世界和区块坐标).
     * 这个任务会被安排到本 Tick 末执行.
     *
     * @param runnable 需要执行的任务.
     */
    default void runDelayed(Runnable runnable) {
        runDelayed(runnable, null, 0, 0);
    }

    /**
     * 在指定世界和区块坐标的区域上下文中延迟执行一个任务.
     *
     * @param runnable 需要执行的任务.
     * @param delay    延迟时间 (以 tick 为单位).
     * @param world    任务执行所在的世界, 可为 null.
     * @param x        区块的 X 坐标.
     * @param z        区块的 Z 坐标.
     * @return 表示该调度任务的 SchedulerTask 实例, 可用于取消任务.
     */
    SchedulerTask runLater(Runnable runnable, long delay, W world, int x, int z);

    /**
     * 在默认区域上下文中延迟执行一个任务 (不指定世界和区块坐标).
     *
     * @param runnable 需要执行的任务.
     * @param delay    延迟时间 (以 tick 为单位).
     * @return 表示该调度任务的 SchedulerTask 实例, 可用于取消任务.
     */
    default SchedulerTask runLater(Runnable runnable, long delay) {
        return runLater(runnable, delay, null, 0 ,0);
    }

    /**
     * 在指定世界和区块坐标的区域上下文中以固定频率重复执行一个任务.
     *
     * @param runnable 需要重复执行的任务.
     * @param delay    首次执行前的延迟时间 (以 tick 为单位).
     * @param period   两次执行之间的间隔时间 (以 tick 为单位).
     * @param world    任务执行所在的世界, 可为 null.
     * @param x        区块的 X 坐标.
     * @param z        区块的 Z 坐标.
     * @return 表示该调度任务的 SchedulerTask 实例, 可用于取消任务.
     */
    SchedulerTask runRepeating(Runnable runnable, long delay, long period, W world, int x, int z);

    /**
     * 在默认区域上下文中以固定频率重复执行一个任务 (不指定世界和区块坐标).
     *
     * @param runnable 需要重复执行的任务.
     * @param delay    首次执行前的延迟时间 (以 tick 为单位).
     * @param period   两次执行之间的间隔时间 (以 tick 为单位).
     * @return 表示该调度任务的 SchedulerTask 实例, 可用于取消任务.
     */
    default SchedulerTask runRepeating(Runnable runnable, long delay, long period) {
        return runRepeating(runnable, delay, period, null, 0, 0);
    }

    /**
     * 在异步线程池中以固定频率重复执行一个任务.
     *
     * @param runnable 需要重复执行的任务.
     * @param delay    首次执行前的延迟时间 (以 tick 为单位).
     * @param period   两次执行之间的间隔时间 (以 tick 为单位).
     * @return 表示该调度任务的 SchedulerTask 实例, 可用于取消任务.
     */
    SchedulerTask runAsyncRepeating(Runnable runnable, long delay, long period);

    /**
     * 在异步线程池中延迟执行一个任务.
     *
     * @param runnable 需要执行的任务.
     * @param delay    延迟时间 (以 tick 为单位).
     * @return 表示该调度任务的 SchedulerTask 实例, 可用于取消任务.
     */
    SchedulerTask runAsyncLater(Runnable runnable, long delay);

}