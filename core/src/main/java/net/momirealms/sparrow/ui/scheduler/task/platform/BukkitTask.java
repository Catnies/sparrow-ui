package net.momirealms.sparrow.ui.scheduler.task.platform;

import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.jetbrains.annotations.NotNull;

/**
 * 将 BukkitTask 适配为通用 SchedulerTask.
 */
public final class BukkitTask implements SchedulerTask {
    private final org.bukkit.scheduler.BukkitTask task;

    /**
     * 包装 Bukkit 平台任务.
     *
     * @param task Bukkit 平台任务
     */
    public BukkitTask(@NotNull org.bukkit.scheduler.BukkitTask task) {
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
