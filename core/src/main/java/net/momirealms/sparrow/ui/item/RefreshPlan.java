package net.momirealms.sparrow.ui.item;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Window 单一 tick 任务使用的不可变周期刷新计划.
 *
 * <p>一个计划可以精确组合多个周期. 例如轮播每 5 tick 换帧，同时用户要求每 7 tick
 * 重新计算上下文时，计划会在 5 或 7 的倍数到期.</p>
 */
public final class RefreshPlan {
    private static final RefreshPlan NONE = new RefreshPlan(new int[0]); // 共享的永不到期计划

    private final int[] periods; // 到期周期(tick), 已去重并升序排列; 空数组表示永不到期.

    /**
     * 创建刷新计划.
     * 调用方必须保证传入数组不会被后续修改.
     *
     * @param periods 到期周期数组, 已去重排序
     */
    private RefreshPlan(int[] periods) {
        this.periods = periods;
    }

    /**
     * 获取永不到期的计划.
     *
     * @return 共享空计划
     */
    public static RefreshPlan none() {
        return NONE;
    }

    /**
     * 创建按固定 tick 周期到期的计划.
     *
     * @param periodTicks 正数 tick 周期
     * @return 刷新计划
     * @throws IllegalArgumentException 周期不是正数时抛出
     */
    public static RefreshPlan every(int periodTicks) {
        if (periodTicks <= 0)
            throw new IllegalArgumentException("periodTicks must be positive");

        return new RefreshPlan(new int[]{periodTicks});
    }

    /**
     * 组合两个计划. 任一计划到期时, 结果计划均到期.
     *
     * @param other 另一个计划
     * @return 去重后的组合计划
     */
    public RefreshPlan or(@NotNull RefreshPlan other) {
        // 空计划与任何计划组合都等价于另一方, 直接复用实例
        if (this.periods.length == 0) return other;
        if (other.periods.length == 0) return this;

        // 合并去重两个计划的周期, 结果与任一输入相同时复用该实例, 避免等价计划产生新对象
        int[] merged = IntStream.concat(Arrays.stream(this.periods), Arrays.stream(other.periods))
                .distinct()
                .sorted()
                .toArray();
        return Arrays.equals(merged, this.periods)
                ? this
                : Arrays.equals(merged, other.periods) ? other : new RefreshPlan(merged);
    }

    /**
     * 判断指定服务器 tick 是否需要周期刷新.
     *
     * @param currentTick 当前服务器 tick
     * @return 任一周期在当前 tick 到期时返回 true
     */
    public boolean isDue(long currentTick) {
        for (int period : this.periods) {
            if (Math.floorMod(currentTick, period) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断此计划是否永不到期.
     *
     * @return 没有周期时返回 true
     */
    public boolean isEmpty() {
        return this.periods.length == 0;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RefreshPlan plan && Arrays.equals(this.periods, plan.periods);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.periods);
    }

    @Override
    public String toString() {
        return "RefreshPlan" + Arrays.toString(this.periods);
    }
}
