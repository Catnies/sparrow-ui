package net.momirealms.sparrow.ui.state;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

// 防抖与节流共用的一次性延时入口, 测试可替换实际调度器
interface Delayer {

    // 在 delay 个时间单位后执行一次, delay 必须为正
    @NotNull
    Handle schedule(@NotNull Runnable task, long delay);

    // tick 时基, 任务运行在 Paper 全局区域调度线程
    @NotNull
    static Delayer paperTicks() {
        return (task, delayTicks) -> {
            Plugin plugin = SparrowUI.getInstance().getPlugin();
            ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignoredTask -> task.run(), delayTicks);
            return scheduled::cancel;
        };
    }

    // 毫秒时基, 任务运行在 Paper 异步调度线程
    @NotNull
    static Delayer paperMillis() {
        return (task, delayMillis) -> {
            Plugin plugin = SparrowUI.getInstance().getPlugin();
            ScheduledTask scheduled = Bukkit.getAsyncScheduler().runDelayed(plugin, ignoredTask -> task.run(), delayMillis, TimeUnit.MILLISECONDS);
            return scheduled::cancel;
        };
    }

    interface Handle {

        void cancel();
    }
}
