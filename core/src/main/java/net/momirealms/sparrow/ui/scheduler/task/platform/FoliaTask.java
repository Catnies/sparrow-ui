package net.momirealms.sparrow.ui.scheduler.task.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.jetbrains.annotations.NotNull;

/**
 * 将 Paper ScheduledTask 适配为通用 SchedulerTask.
 */
public final class FoliaTask implements SchedulerTask {
    private final ScheduledTask task;

    /**
     * 包装 Paper/Folia 平台任务.
     *
     * @param task Paper/Folia 平台任务
     */
    public FoliaTask(@NotNull ScheduledTask task) {
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
