package net.momirealms.sparrow.ui.scheduler.task;

/**
 * 空操作的调度任务实现.
 */
public final class DummyTask implements SchedulerTask {

    @Override
    public void cancel() {
    }

    @Override
    public boolean cancelled() {
        return true;
    }
}