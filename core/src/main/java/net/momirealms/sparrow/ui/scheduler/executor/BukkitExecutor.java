package net.momirealms.sparrow.ui.scheduler.executor;

import net.momirealms.sparrow.ui.scheduler.task.DummyTask;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.scheduler.task.platform.BukkitTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 使用经典 BukkitScheduler 实现同步和异步 tick 调度.
 */
public final class BukkitExecutor implements RegionExecutor<World> {
    private final Plugin plugin;

    /**
     * 创建由指定插件拥有的 Bukkit 执行器.
     *
     * @param plugin 任务所属插件
     */
    public BukkitExecutor(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run(@NotNull Runnable runnable, @Nullable World world, int x, int z) {
        this.execute(runnable);
    }

    @Override
    public void runDelayed(@NotNull Runnable runnable, @Nullable World world, int x, int z) {
        Bukkit.getScheduler().runTask(this.plugin, runnable);
    }

    @Override
    @NotNull
    public SchedulerTask runAsyncRepeating(@NotNull Runnable runnable, long delay, long period) {
        return new BukkitTask(Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, runnable, delay, period));
    }

    @Override
    @NotNull
    public SchedulerTask runAsyncLater(@NotNull Runnable runnable, long delay) {
        return new BukkitTask(Bukkit.getScheduler().runTaskLaterAsynchronously(this.plugin, runnable, delay));
    }

    @Override
    @NotNull
    public SchedulerTask runLater(@NotNull Runnable runnable, long delay, @Nullable World world, int x, int z) {
        if (delay <= 0) {
            if (Bukkit.isPrimaryThread()) {
                runnable.run();
                return new DummyTask();
            }
            return new BukkitTask(Bukkit.getScheduler().runTask(this.plugin, runnable));
        }
        return new BukkitTask(Bukkit.getScheduler().runTaskLater(this.plugin, runnable, delay));
    }

    @Override
    @NotNull
    public SchedulerTask runRepeating(@NotNull Runnable runnable, long delay, long period, @Nullable World world, int x, int z) {
        return new BukkitTask(Bukkit.getScheduler().runTaskTimer(this.plugin, runnable, delay, period));
    }

    @Override
    public void execute(@NotNull Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, runnable);
    }
}
