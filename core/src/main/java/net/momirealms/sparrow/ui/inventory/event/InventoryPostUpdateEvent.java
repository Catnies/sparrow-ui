package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Inventory 在事务提交后发出的更新事件.
 * <p>{@link #slotChanges()} 是投影到当前订阅 Inventory 后的槽位变更,
 * {@link #rootChanges()} 保留整笔事务涉及的所有 RootInventory 变更.
 */
public final class InventoryPostUpdateEvent extends InventoryUpdateEvent {

    @ApiStatus.Internal
    public InventoryPostUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges
    ) {
        super(inventory, reason, slotChanges, rootChanges);
    }
}
