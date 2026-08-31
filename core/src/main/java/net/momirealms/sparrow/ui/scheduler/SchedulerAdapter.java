package net.momirealms.sparrow.ui.scheduler;

import net.momirealms.sparrow.ui.scheduler.executor.RegionExecutor;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 提供同步区域调度、异步执行和墙钟定时任务.
 *
 * @param <W> 世界类型
 */
public interface SchedulerAdapter<W> {

    /**
     * 返回异步工作执行器.
     *
     * @return 异步工作执行器
     */
    @NotNull
    Executor async();

    /**
     * 返回同步区域执行器.
     *
     * @return 同步区域执行器
     */
    @NotNull
    RegionExecutor<W> sync();

    /**
     * 在默认同步上下文执行任务.
     *
     * @param task 要执行的任务
     */
    default void executeSync(@NotNull Runnable task) {
        this.sync().run(task, null, 0, 0);
    }

    /**
     * 在指定区域同步执行任务.
     *
     * @param task 要执行的任务
     * @param world 目标世界
     * @param x 目标区块 X 坐标
     * @param z 目标区块 Z 坐标
     */
    default void executeSync(@NotNull Runnable task, @NotNull W world, int x, int z) {
        this.sync().run(task, world, x, z);
    }

    /**
     * 在异步工作执行器中执行任务.
     *
     * @param task 要执行的任务
     */
    default void executeAsync(@NotNull Runnable task) {
        this.async().execute(task);
    }

    /**
     * 按墙钟时间延迟执行异步任务.
     *
     * @param task 要执行的任务
     * @param delay 延迟时间
     * @param unit 时间单位
     * @return 可取消的调度任务
     */
    @NotNull
    SchedulerTask asyncLater(@NotNull Runnable task, long delay, @NotNull TimeUnit unit);

    /**
     * 按墙钟时间重复执行异步任务.
     *
     * @param task 要执行的任务
     * @param delay 首次执行前的延迟
     * @param interval 相邻执行之间的间隔
     * @param unit 时间单位
     * @return 可取消的调度任务
     */
    @NotNull
    SchedulerTask asyncRepeating(@NotNull Runnable task, long delay, long interval, @NotNull TimeUnit unit);

    /**
     * 按墙钟时间重复执行可以取消自身的异步任务.
     *
     * @param task 接收自身任务句柄的回调
     * @param delay 首次执行前的延迟
     * @param interval 相邻执行之间的间隔
     * @param unit 时间单位
     * @return 可取消的调度任务
     */
    @NotNull
    SchedulerTask asyncRepeating(@NotNull Consumer<SchedulerTask> task, long delay, long interval, @NotNull TimeUnit unit);

    /**
     * 停止墙钟定时器并等待已提交的定时回调退出.
     */
    void shutdownScheduler();

    /**
     * 停止异步工作执行器并等待已提交的任务退出.
     */
    void shutdownExecutor();
}
