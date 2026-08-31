package net.momirealms.sparrow.ui.scheduler.executor;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.scheduler.task.platform.FoliaTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 使用 Paper 实体调度器跟随实体迁移和退役生命周期.
 */
public final class FoliaEntityExecutor implements EntityExecutor {
    private final Plugin plugin;

    /**
     * 创建由指定插件拥有的实体执行器.
     *
     * @param plugin 任务所属插件
     */
    public FoliaEntityExecutor(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    @Override
    @Nullable
    public SchedulerTask run(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        ScheduledTask scheduled = entity.getScheduler().run(this.plugin, ignoredTask -> task.run(), retired);
        return scheduled == null ? null : new FoliaTask(scheduled);
    }

    @Override
    @Nullable
    public SchedulerTask runAtFixedRate(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired, long initialDelay, long period) {
        ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(this.plugin, ignoredTask -> task.run(), retired, initialDelay, period);
        return scheduled == null ? null : new FoliaTask(scheduled);
    }
}
