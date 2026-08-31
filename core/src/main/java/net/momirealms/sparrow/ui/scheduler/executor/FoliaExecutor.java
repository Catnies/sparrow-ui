package net.momirealms.sparrow.ui.scheduler.executor;

import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.scheduler.task.platform.FoliaTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * 使用 Folia 全局与区域调度器实现同步 tick 调度.
 */
public final class FoliaExecutor implements RegionExecutor<World> {
    private final Plugin plugin;

    /**
     * 创建由指定插件拥有的 Folia 区域执行器.
     *
     * @param plugin 任务所属插件
     */
    public FoliaExecutor(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run(@NotNull Runnable runnable, @Nullable World world, int x, int z) {
        if (world == null) {
            Bukkit.getGlobalRegionScheduler().execute(this.plugin, runnable);
        } else {
            Bukkit.getRegionScheduler().execute(this.plugin, world, x, z, runnable);
        }
    }

    @Override
    public void runDelayed(@NotNull Runnable runnable, @Nullable World world, int x, int z) {
        this.run(runnable, world, x, z);
    }

    @Override
    @NotNull
    public SchedulerTask runAsyncRepeating(@NotNull Runnable runnable, long delay, long period) {
        return new FoliaTask(Bukkit.getAsyncScheduler().runAtFixedRate(
                this.plugin,
                ignoredTask -> runnable.run(),
                delay * 50L,
                period * 50L,
                TimeUnit.MILLISECONDS
        ));
    }

    @Override
    @NotNull
    public SchedulerTask runAsyncLater(@NotNull Runnable runnable, long delay) {
        return new FoliaTask(Bukkit.getAsyncScheduler().runDelayed(
                this.plugin,
                ignoredTask -> runnable.run(),
                delay * 50L,
                TimeUnit.MILLISECONDS
        ));
    }

    @Override
    @NotNull
    public SchedulerTask runLater(@NotNull Runnable runnable, long delay, @Nullable World world, int x, int z) {
        if (world == null) {
            return delay <= 0
                    ? new FoliaTask(Bukkit.getGlobalRegionScheduler().run(this.plugin, ignoredTask -> runnable.run()))
                    : new FoliaTask(Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, ignoredTask -> runnable.run(), delay));
        }
        return delay <= 0
                ? new FoliaTask(Bukkit.getRegionScheduler().run(this.plugin, world, x, z, ignoredTask -> runnable.run()))
                : new FoliaTask(Bukkit.getRegionScheduler().runDelayed(this.plugin, world, x, z, ignoredTask -> runnable.run(), delay));
    }

    @Override
    @NotNull
    public SchedulerTask runRepeating(@NotNull Runnable runnable, long delay, long period, @Nullable World world, int x, int z) {
        if (world == null) {
            return new FoliaTask(Bukkit.getGlobalRegionScheduler().runAtFixedRate(this.plugin, ignoredTask -> runnable.run(), delay, period));
        }
        return new FoliaTask(Bukkit.getRegionScheduler().runAtFixedRate(this.plugin, world, x, z, ignoredTask -> runnable.run(), delay, period));
    }

    @Override
    public void execute(@NotNull Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, runnable);
    }
}
