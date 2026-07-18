package net.momirealms.sparrow.ui.scheduler.task;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ScheduledFuture;

public final class LazyAsyncTask implements SchedulerTask {
    @Nullable
    public ScheduledFuture<?> future;

    @Override
    public void cancel() {
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public boolean cancelled() {
        if (future == null) return false;
        return future.isCancelled();
    }
}