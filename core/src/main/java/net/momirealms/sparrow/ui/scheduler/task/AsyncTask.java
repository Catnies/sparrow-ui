package net.momirealms.sparrow.ui.scheduler.task;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ScheduledFuture;

/**
 * 包装墙钟定时器返回的 ScheduledFuture.
 */
public final class AsyncTask implements SchedulerTask {
    private final ScheduledFuture<?> future;

    /**
     * 包装指定定时任务.
     *
     * @param future 定时任务
     */
    public AsyncTask(@NotNull ScheduledFuture<?> future) {
        this.future = future;
    }

    @Override
    public void cancel() {
        this.future.cancel(false);
    }

    @Override
    public boolean cancelled() {
        return this.future.isCancelled();
    }
}
