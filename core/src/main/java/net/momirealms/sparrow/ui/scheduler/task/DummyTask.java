package net.momirealms.sparrow.ui.scheduler.task;

/**
 * 表示已经在调用线程内完成的任务.
 */
public final class DummyTask implements SchedulerTask {
    private volatile boolean cancelled;

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    @Override
    public boolean cancelled() {
        return this.cancelled;
    }
}
