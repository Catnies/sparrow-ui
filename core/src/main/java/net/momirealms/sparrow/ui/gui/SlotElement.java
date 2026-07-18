package net.momirealms.sparrow.ui.gui;

import net.momirealms.sparrow.ui.item.PeriodicItem;

import java.util.Objects;

public sealed interface SlotElement permits SlotElement.Item {

    /**
     * 获取此元素最终内容的周期更新频率.
     *
     * @return 正数 tick 周期；不需要周期更新时返回 {@link PeriodicItem#NO_PERIODIC_UPDATE}
     */
    int updatePeriodTicks();

    /**
     * 直接持有一个可渲染、可点击的 Item.
     */
    record Item(net.momirealms.sparrow.ui.item.Item item) implements SlotElement {

        public Item {
            Objects.requireNonNull(item, "item");
        }

        @Override
        public int updatePeriodTicks() {
            return item instanceof PeriodicItem periodic
                    ? periodic.updatePeriodTicks()
                    : PeriodicItem.NO_PERIODIC_UPDATE;
        }
    }
}
