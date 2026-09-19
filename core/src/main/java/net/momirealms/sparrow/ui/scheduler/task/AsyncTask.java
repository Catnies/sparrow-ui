package net.momirealms.sparrow.ui.scheduler.task;

import java.util.concurrent.ScheduledFuture;

public final class AsyncTask implements SchedulerTask {
    private final ScheduledFuture<?> future;

    /**
     * 包装指定定时任务.
     *
     * @param future 定时任务
     */
    public AsyncTask(ScheduledFuture<?> future) {
        this.future = future;
    }

    @Override
    public void cancel() {
        future.cancel(false);
    }

    @Override
    public boolean cancelled() {
        return future.isCancelled();
    }
}
