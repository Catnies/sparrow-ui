package net.momirealms.sparrow.ui.scheduler.task;

/**
 * 表示一个可调度的任务.
 * 提供取消任务和查询任务取消状态的能力.
 */
public interface SchedulerTask {

    /**
     * 取消该任务的执行.
     */
    void cancel();

    /**
     * 检查该任务是否已被取消.
     *
     * @return 如果任务已被取消则返回 true, 否则返回 false.
     */
    boolean cancelled();
}