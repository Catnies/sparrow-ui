package net.momirealms.sparrow.ui.scheduler.task;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ScheduledFuture;

/**
 * 支持在底层 Future 绑定前请求取消的异步任务.
 */
public final class LazyAsyncTask implements SchedulerTask {
    private volatile @Nullable ScheduledFuture<?> future;
    private volatile boolean cancelRequested;

    /**
     * 绑定定时器创建的 Future.
     *
     * @param future 定时任务
     */
    public void future(@NotNull ScheduledFuture<?> future) {
        this.future = future;
        if (this.cancelRequested) {
            future.cancel(false);
        }
    }

    @Override
    public void cancel() {
        this.cancelRequested = true;
        ScheduledFuture<?> future = this.future;
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public boolean cancelled() {
        ScheduledFuture<?> future = this.future;
        return this.cancelRequested || future != null && future.isCancelled();
    }
}
