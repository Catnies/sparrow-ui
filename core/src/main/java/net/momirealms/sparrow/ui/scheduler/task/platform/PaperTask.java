package net.momirealms.sparrow.ui.scheduler.task.platform;

import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 将 Paper 调度任务适配为 SparrowUI 的平台无关任务句柄.
 */
@ApiStatus.Internal
public final class PaperTask implements SchedulerTask {
    private final io.papermc.paper.threadedregions.scheduler.ScheduledTask task;

    /**
     * 包装一个 Paper 调度任务.
     *
     * @param task Paper 调度任务
     */
    public PaperTask(@NotNull io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
        this.task = task;
    }

    @Override
    public void cancel() {
        this.task.cancel();
    }

    @Override
    public boolean cancelled() {
        return this.task.isCancelled();
    }
}
