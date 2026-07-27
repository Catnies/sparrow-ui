package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Window 内部使用的 Paper 调度入口.
 */
final class WindowScheduler {
    private final Plugin plugin;
    private final AsyncScheduler async = new AsyncScheduler();
    private final EntityScheduler entity = new EntityScheduler();

    WindowScheduler(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @NotNull
    AsyncScheduler async() {
        return this.async;
    }

    @NotNull
    EntityScheduler entity() {
        return this.entity;
    }

    final class AsyncScheduler {

        @NotNull
        ScheduledTask runNow(@NotNull Runnable task) {
            return Bukkit.getAsyncScheduler().runNow(WindowScheduler.this.plugin, ignoredTask -> task.run());
        }

        @NotNull
        ScheduledTask runAtFixedRate(@NotNull Consumer<ScheduledTask> task, long initialDelay, long period, @NotNull TimeUnit timeUnit) {
            return Bukkit.getAsyncScheduler().runAtFixedRate(WindowScheduler.this.plugin, task, initialDelay, period, timeUnit);
        }
    }

    final class EntityScheduler {

        boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
            return Bukkit.isOwnedByCurrentRegion(entity);
        }

        @Nullable
        ScheduledTask run(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
            return entity.getScheduler().run(WindowScheduler.this.plugin, ignoredTask -> task.run(), retired);
        }

        @Nullable
        ScheduledTask runAtFixedRate(@NotNull Entity entity, @NotNull Consumer<ScheduledTask> task, @NotNull Runnable retired, long initialDelay, long period) {
            return entity.getScheduler().runAtFixedRate(WindowScheduler.this.plugin, task, retired, initialDelay, period);
        }
    }
}
