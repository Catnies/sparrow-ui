package net.momirealms.sparrow.ui.state;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

// 一次性延时任务的调度抽象, 防抖与节流节点靠它在 tick 与毫秒两种时基上共用同一套实现.
interface Delayer {

    // 在 delay 个时间单位之后跑一次 task, 返回可取消的句柄. delay 必须为正.
    @NotNull
    Handle schedule(@NotNull Runnable task, long delay);

    // tick 基, 任务跑在 Paper 全局区域调度线程.
    @NotNull
    static Delayer paperTicks() {
        return (task, delayTicks) -> {
            Plugin plugin = SparrowUI.getInstance().getPlugin();
            ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignoredTask -> task.run(), delayTicks);
            return scheduled::cancel;
        };
    }

    // 毫秒基, 任务跑在 Paper 异步调度线程.
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
