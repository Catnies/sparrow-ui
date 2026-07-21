package net.momirealms.sparrow.ui.scheduler.executor;

import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.scheduler.task.platform.PaperTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 使用 Folia 区域与全局区域调度器执行同步任务.
 */
public final class FoliaExecutor implements RegionExecutor<World> {
    private final Plugin plugin;

    public FoliaExecutor(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run(Runnable runnable, World world, int x, int z) {
        Optional.ofNullable(world).ifPresentOrElse(w ->
                Bukkit.getRegionScheduler().execute(this.plugin, w, x, z, runnable),
                () -> Bukkit.getGlobalRegionScheduler().execute(this.plugin, runnable)
        );
    }

    @Override
    public void runDelayed(Runnable runnable, World world, int x, int z) {
        this.run(runnable, world, x, z);
    }

    @Override
    public SchedulerTask runAsyncRepeating(Runnable runnable, long delay, long period) {
        return this.runRepeating(runnable, delay, period, null, 0, 0);
    }

    @Override
    public SchedulerTask runAsyncLater(Runnable runnable, long delay) {
        return this.runLater(runnable, delay, null, 0, 0);
    }

    @Override
    public SchedulerTask runLater(Runnable runnable, long delay, World world, int x, int z) {
        if (world == null) {
            if (delay <= 0) {
                return new PaperTask(Bukkit.getGlobalRegionScheduler().run(this.plugin, scheduledTask -> runnable.run()));
            } else {
                return new PaperTask(Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, scheduledTask -> runnable.run(), delay));
            }
        } else {
            if (delay <= 0) {
                return new PaperTask(Bukkit.getRegionScheduler().run(this.plugin, world, x, z, scheduledTask -> runnable.run()));
            } else {
                return new PaperTask(Bukkit.getRegionScheduler().runDelayed(this.plugin, world, x, z, scheduledTask -> runnable.run(), delay));
            }
        }
    }

    @Override
    public SchedulerTask runRepeating(Runnable runnable, long delay, long period, World world, int x, int z) {
        if (world == null) {
            return new PaperTask(Bukkit.getGlobalRegionScheduler().runAtFixedRate(this.plugin, scheduledTask -> runnable.run(), delay, period));
        } else {
            return new PaperTask(Bukkit.getRegionScheduler().runAtFixedRate(this.plugin, world, x, z, scheduledTask -> runnable.run(), delay, period));
        }
    }

    @Override
    public void execute(@NotNull Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, runnable);
    }
}
