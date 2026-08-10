package net.momirealms.sparrow.ui.item.guard;

import net.momirealms.sparrow.ui.item.click.ItemClick;
import org.jetbrains.annotations.NotNull;

public final class ItemClickGuards {
    private ItemClickGuards() {
    }

    /**
     * 创建按 Item 与玩家分别计时的节流规则.
     * <p>首次点击立即通过, 限制期内的拒绝不会延长间隔.</p>
     *
     * @param intervalMillis 两次有效点击之间至少间隔的毫秒数
     * @return 节流守卫
     * @throws IllegalArgumentException 间隔不是正数时抛出
    */
    @NotNull
    public static ItemGuard<ItemClick> throttle(long intervalMillis) {
        return new ThrottleGuard(intervalMillis, System::currentTimeMillis);
    }
}
