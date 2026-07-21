package net.momirealms.sparrow.ui.scheduler.executor;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 在 Bukkit 实体所有者线程执行任务的调度接口.
 *
 * <p>除一次性和周期任务外, 该接口还保留实体退役与当前线程所有权语义. 调用方可据此
 * 安全地串行化实体状态变更, 并在实体不再可调度时转入不访问实体的释放路径.</p>
 */
@ApiStatus.Internal
public interface EntityExecutor {

    /**
     * 判断当前线程是否拥有目标实体.
     *
     * @param entity 目标实体
     * @return 当前线程是否可直接访问实体状态
     */
    boolean isOwnedByCurrentThread(@NotNull Entity entity);

    /**
     * 向目标实体的所有者线程提交一次任务.
     *
     * @param entity 目标实体
     * @param task 实体仍可调度时执行的任务
     * @param retired 实体已退役时执行的回调
     * @return 调度器是否接受该任务
     */
    boolean execute(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired);

    /**
     * 在目标实体的所有者线程按 tick 周期重复执行任务.
     *
     * @param entity 目标实体
     * @param task 每个周期执行的任务
     * @param retired 实体已退役时执行的回调
     * @param initialDelay 首次执行前的 tick 数
     * @param period 两次执行之间的 tick 数
     * @return 已创建的任务, 调度失败时为 null
     */
    @Nullable SchedulerTask runAtFixedRate(
            @NotNull Entity entity,
            @NotNull Consumer<ScheduledTask> task,
            @NotNull Runnable retired,
            long initialDelay,
            long period
    );
}
