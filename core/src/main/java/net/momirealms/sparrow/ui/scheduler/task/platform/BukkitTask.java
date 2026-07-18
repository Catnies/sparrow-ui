package net.momirealms.sparrow.ui.scheduler.task.platform;

import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;

public final class BukkitTask implements SchedulerTask {
    private final org.bukkit.scheduler.BukkitTask bukkitTask;

    public BukkitTask(org.bukkit.scheduler.BukkitTask bukkitTask) {
        this.bukkitTask = bukkitTask;
    }

    @Override
    public void cancel() {
        this.bukkitTask.cancel();
    }

    @Override
    public boolean cancelled() {
        return bukkitTask.isCancelled();
    }
}
