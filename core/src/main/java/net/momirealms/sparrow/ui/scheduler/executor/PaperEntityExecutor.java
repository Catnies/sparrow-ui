package net.momirealms.sparrow.ui.scheduler.executor;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.scheduler.task.platform.PaperTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 基于 Paper {@link io.papermc.paper.threadedregions.scheduler.EntityScheduler} 的实体执行器.
 */
@ApiStatus.Internal
public final class PaperEntityExecutor implements EntityExecutor {
    private final Plugin plugin;

    public PaperEntityExecutor(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isOwnedByCurrentThread(@NotNull Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    @Override
    public boolean execute(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        return entity.getScheduler().execute(this.plugin, task, retired, 1);
    }

    @Override
    public @Nullable SchedulerTask runAtFixedRate(
            @NotNull Entity entity,
            @NotNull Consumer<ScheduledTask> task,
            @NotNull Runnable retired,
            long initialDelay,
            long period
    ) {
        var scheduledTask = entity.getScheduler().runAtFixedRate(this.plugin, task, retired, initialDelay, period);
        return scheduledTask == null ? null : new PaperTask(scheduledTask);
    }
}
