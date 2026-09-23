package net.momirealms.sparrow.ui.state.internal.time;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

// 防抖与节流共用的一次性延时入口, 测试可替换实际调度器
@ApiStatus.Internal
public interface Delayer {

    // 在 delay 个时间单位后执行一次, delay 必须为正
    @NotNull
    Handle schedule(@NotNull Runnable task, long delay);

    // tick 时基, Bukkit 主线程或 Folia 全局区域线程执行.
    @NotNull
    static Delayer ticks() {
        return (task, delayTicks) -> {
            SchedulerTask scheduled = SparrowUI.getInstance().scheduler().platform().runLater(task, delayTicks);
            return scheduled::cancel;
        };
    }

    // 毫秒时基, 任务运行在库的异步工作执行器上.
    @NotNull
    static Delayer millis() {
        return (task, delayMillis) -> {
            SchedulerTask scheduled = SparrowUI.getInstance().scheduler().asyncLater(task, delayMillis, TimeUnit.MILLISECONDS);
            return scheduled::cancel;
        };
    }

    interface Handle {

        void cancel();
    }
}
