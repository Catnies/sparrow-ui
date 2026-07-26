package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一次事务中单个库存的全部槽位变更.
 * <p>事务事件的载荷由若干本类型组成, 观察者因此总能看到跨库存事务的完整画面.
 *
 * @param inventory 发生变更的库存
 * @param deltas 该库存的槽位变更, 不可变列表
 */
public record InventoryDelta(@NotNull Inventory inventory, @NotNull List<SlotDelta> deltas) {

    public InventoryDelta {
        deltas = List.copyOf(deltas);
    }
}
