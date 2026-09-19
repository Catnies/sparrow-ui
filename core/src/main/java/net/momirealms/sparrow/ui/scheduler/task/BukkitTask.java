package net.momirealms.sparrow.ui.scheduler.task;

public final class BukkitTask implements SchedulerTask {
    private final org.bukkit.scheduler.BukkitTask bukkitTask;

    /**
     * 包装 Bukkit 平台任务.
     *
     * @param bukkitTask Bukkit 平台任务
     */
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
