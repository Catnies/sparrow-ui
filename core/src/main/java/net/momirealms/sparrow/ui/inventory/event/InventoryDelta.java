package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一次事务中单个Inventory的全部槽位变更.
 *
 * @param inventory 发生变更的Inventory
 * @param deltas 该Inventory的槽位变更, 不可变列表
 */
public record InventoryDelta(
        @NotNull Inventory inventory,
        @NotNull List<SlotDelta> deltas
) {

    public InventoryDelta {
        deltas = List.copyOf(deltas);
    }
}
