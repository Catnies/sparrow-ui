package net.momirealms.sparrow.ui.scheduler.executor;

import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 在实体所属执行上下文中调度任务, 平台支持时同时转发实体退役通知.
 */
public interface EntityExecutor {

    /**
     * 返回当前线程是否拥有实体的执行权.
     *
     * @param entity 目标实体
     * @return 当前线程拥有执行权时返回 {@code true}
     */
    boolean isOwnedByCurrentRegion(@NotNull Entity entity);

    /**
     * 安排一次实体任务.
     *
     * @param entity 目标实体
     * @param task 实体可用时执行的任务
     * @param retired 实体退役时执行的回调. <strong>Paper/Folia 可能在 critical retirement context 调用, 回调只能释放本地状态</strong>
     * @return 平台接受的任务, 支持拒绝退役实体的平台可能返回 {@code null}
     */
    @Nullable
    SchedulerTask run(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired);

    /**
     * 安排重复实体任务.
     *
     * @param entity 目标实体
     * @param task 每个周期执行的任务
     * @param retired 实体退役时执行的回调. <strong>Paper/Folia 可能在 critical retirement context 调用, 回调只能释放本地状态</strong>
     * @param initialDelay 首次执行前的 tick 数
     * @param period 相邻执行之间的 tick 数
     * @return 平台接受的任务, 支持拒绝退役实体的平台可能返回 {@code null}
     */
    @Nullable
    SchedulerTask runAtFixedRate(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired, long initialDelay, long period);
}
