package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class WindowScheduler {
    private final Plugin plugin;            // 调度任务所属的插件实例
    private final AsyncScheduler async;     // 全局异步调度入口
    private final EntityScheduler entity;   // 实体区域调度入口

    WindowScheduler(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.async = new AsyncScheduler();
        this.entity = new EntityScheduler();
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

        /**
         * 立即在异步线程执行一次任务.
         *
         * @param task 要执行的任务
         * @return 任务句柄
         */
        @NotNull
        ScheduledTask runNow(@NotNull Runnable task) {
            return Bukkit.getAsyncScheduler().runNow(WindowScheduler.this.plugin, ignoredTask -> task.run());
        }

        /**
         * 在异步线程按固定频率执行任务.
         *
         * @param task 要执行的任务, 接收任务句柄
         * @param initialDelay 首次执行前的延迟
         * @param period 两次执行的间隔
         * @param timeUnit 延迟与间隔的时间单位
         * @return 任务句柄
         */
        @NotNull
        ScheduledTask runAtFixedRate(@NotNull Consumer<ScheduledTask> task, long initialDelay, long period, @NotNull TimeUnit timeUnit) {
            return Bukkit.getAsyncScheduler().runAtFixedRate(WindowScheduler.this.plugin, task, initialDelay, period, timeUnit);
        }
    }

    final class EntityScheduler {

        /**
         * 判断当前线程是否拥有指定实体所在的区域, 即能否直接内联操作该实体.
         *
         * @param entity 要检查的实体
         * @return 当前线程拥有实体区域时为 true
         */
        boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
            return Bukkit.isOwnedByCurrentRegion(entity);
        }

        /**
         * 在实体所在区域的线程执行一次任务.
         *
         * @param entity 目标实体
         * @param task 要执行的任务
         * @param retired 实体在任务执行前退役时改用的回调
         * @return 任务句柄, 实体已退役无法调度时为 null
         */
        @Nullable
        ScheduledTask run(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
            return entity.getScheduler().run(WindowScheduler.this.plugin, ignoredTask -> task.run(), retired);
        }

        /**
         * 在实体所在区域的线程按固定 tick 频率执行任务.
         *
         * @param entity 目标实体
         * @param task 要执行的任务, 接收任务句柄
         * @param retired 实体在任务执行前退役时改用的回调
         * @param initialDelay 首次执行前的 tick 数
         * @param period 两次执行的 tick 间隔
         * @return 任务句柄, 实体已退役无法调度时为 null
         */
        @Nullable
        ScheduledTask runAtFixedRate(@NotNull Entity entity, @NotNull Consumer<ScheduledTask> task, @NotNull Runnable retired, long initialDelay, long period) {
            return entity.getScheduler().runAtFixedRate(WindowScheduler.this.plugin, task, retired, initialDelay, period);
        }
    }
}
