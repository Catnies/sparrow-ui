package net.momirealms.sparrow.ui.item;

/**
 * 需要由 Window 按固定 tick 周期重新渲染的 Item.
 *
 * <p>PeriodicItem 自身不创建调度任务. 每个打开的 Window 使用自己的单一 tick 任务
 * 检查当前显示路径, 并在周期到达时重新渲染相应的最终槽位.</p>
 */
public interface PeriodicItem extends Item {
    /** 表示不需要 Window 周期重渲染. */
    int NO_PERIODIC_UPDATE = -1;

    /**
     * 获取重新渲染周期.
     *
     * @return 正数 tick 周期；不需要周期更新时返回 {@link #NO_PERIODIC_UPDATE}
     */
    int updatePeriodTicks();
}
