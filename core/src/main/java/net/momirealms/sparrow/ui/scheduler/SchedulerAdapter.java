package net.momirealms.sparrow.ui.scheduler;

import net.momirealms.sparrow.ui.scheduler.executor.RegionExecutor;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 调度器适配器接口.
 * 提供异步执行, 同步区域执行, 延迟执行和重复执行等调度能力.
 *
 * @param <W> 世界类型参数, 用于指定同步任务执行的区域上下文.
 */
public interface SchedulerAdapter<W> {

    /**
     * 获取异步执行器.
     * 通过该执行器提交的任务将在异步线程池中执行.
     *
     * @return 异步执行器实例.
     */
    Executor async();

    /**
     * 获取同步区域执行器.
     * 通过该执行器提交的任务将在指定世界和区块坐标的主线程上执行.
     *
     * @return 同步区域执行器实例.
     */
    RegionExecutor<W> sync();

    /**
     * 执行一个同步任务.
     *
     * @param task 需要执行的任务.
     */
    default void executeSync(Runnable task) {
        sync().run(task, null, 0, 0);
    }

    /**
     * 执行一个区域同步任务.
     *
     * @param task  需要执行的任务.
     * @param world 任务执行所在的世界.
     * @param x     区块的 X 坐标.
     * @param z     区块的 Z 坐标.
     */
    default void executeSync(Runnable task, W world, int x, int z) {
        sync().run(task, world, x, z);
    }

    /**
     * 在异步线程池中执行一个任务.
     *
     * @param task 需要执行的任务.
     */
    default void executeAsync(Runnable task) {
        async().execute(task);
    }

    /**
     * 在异步线程池中延迟执行一个任务.
     *
     * @param task  需要执行的任务.
     * @param delay 延迟时间.
     * @param unit  延迟时间的单位.
     * @return 表示该调度任务的 SchedulerTask 实例, 可用于取消任务.
     */
    SchedulerTask asyncLater(Runnable task, long delay, TimeUnit unit);

    /**
     * 在异步线程池中以固定频率重复执行一个任务.
     *
     * @param task     需要重复执行的任务.
     * @param delay    首次执行前的延迟时间.
     * @param interval 两次执行之间的间隔时间.
     * @param unit     时间单位.
     * @return 表示该调度任务的 SchedulerTask 实例, 可用于取消任务.
     */
    SchedulerTask asyncRepeating(Runnable task, long delay, long interval, TimeUnit unit);

    /**
     * 在异步线程池中以固定频率重复执行一个任务, 任务可以接收 SchedulerTask 参数以实现自我取消.
     *
     * @param task     需要重复执行的任务, 接收自身的 SchedulerTask 作为参数.
     * @param delay    首次执行前的延迟时间.
     * @param interval 两次执行之间的间隔时间.
     * @param unit     时间单位.
     * @return 表示该调度任务的 SchedulerTask 实例, 可用于取消任务.
     */
    SchedulerTask asyncRepeating(Consumer<SchedulerTask> task, long delay, long interval, TimeUnit unit);

    /**
     * 关闭调度器 (ScheduledThreadPoolExecutor).
     * 停止接受新的调度任务, 并等待已提交的任务完成.
     */
    void shutdownScheduler();

    /**
     * 关闭异步执行器 (ForkJoinPool).
     * 停止接受新的异步任务, 并等待已提交的任务完成.
     */
    void shutdownExecutor();

    /**
     * 依次关闭定时调度器和异步执行器. 即使关闭定时调度器失败,
     * 也仍会尝试关闭异步执行器.
     */
    default void shutdown() {
        try {
            shutdownScheduler();
        } finally {
            shutdownExecutor();
        }
    }
}
