package net.momirealms.sparrow.ui.scheduler.executor;

import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.scheduler.task.platform.BukkitTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在 Spigot/Bukkit 主线程上执行实体任务.
 */
public final class BukkitEntityExecutor implements EntityExecutor {
    private final Plugin plugin;

    /**
     * 创建由指定插件拥有的实体执行器.
     *
     * @param plugin 任务所属插件
     */
    public BukkitEntityExecutor(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
        return Bukkit.isPrimaryThread() && BukkitEntityExecutor.available(entity);
    }

    @Override
    @Nullable
    public SchedulerTask run(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        if (Bukkit.isPrimaryThread() && !BukkitEntityExecutor.available(entity)) {
            return null;
        }
        return new BukkitTask(Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (BukkitEntityExecutor.available(entity)) {
                task.run();
            } else {
                retired.run();
            }
        }));
    }

    @Override
    @Nullable
    public SchedulerTask runAtFixedRate(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired, long initialDelay, long period) {
        if (Bukkit.isPrimaryThread() && !BukkitEntityExecutor.available(entity)) {
            return null;
        }
        AtomicBoolean retiredOnce = new AtomicBoolean();
        BukkitRunnable repeating = new BukkitRunnable() {
            @Override
            public void run() {
                if (BukkitEntityExecutor.available(entity)) {
                    task.run();
                    return;
                }
                this.cancel();
                if (retiredOnce.compareAndSet(false, true)) {
                    retired.run();
                }
            }
        };
        org.bukkit.scheduler.BukkitTask taskHandle = repeating.runTaskTimer(this.plugin, initialDelay, period);
        return new BukkitTask(taskHandle);
    }

    private static boolean available(Entity entity) {
        return entity instanceof Player player ? player.isOnline() : entity.isValid();
    }
}
