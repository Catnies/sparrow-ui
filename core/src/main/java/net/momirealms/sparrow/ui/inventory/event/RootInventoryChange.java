package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一笔事务中单个 RootInventory 的变更组.
 *
 * @param inventory 发生变更的 RootInventory
 * @param slotChanges 使用该 RootInventory 槽位坐标的变更记录, 不可变列表
 */
public record RootInventoryChange(
        @NotNull SparrowInventory inventory,
        @NotNull List<SlotChange> slotChanges
) {

    public RootInventoryChange {
        slotChanges = List.copyOf(slotChanges);
    }
}
