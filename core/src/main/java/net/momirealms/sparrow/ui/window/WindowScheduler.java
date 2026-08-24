package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

final class WindowScheduler {
    private final Plugin plugin;
    private final EntityScheduler entity;

    WindowScheduler(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.entity = new EntityScheduler();
    }

    @NotNull
    EntityScheduler entity() {
        return this.entity;
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
