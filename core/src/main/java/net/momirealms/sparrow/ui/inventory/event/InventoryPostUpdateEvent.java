package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.TransactionScope;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Inventory 在事务提交后发出的更新事件.
 * <p>{@link #slotChanges()} 是当前订阅 Inventory 自己的槽位变更,
 * {@link #rootChanges()} 保留整笔事务涉及的所有 Inventory 变更.
 */
public final class InventoryPostUpdateEvent extends InventoryUpdateEvent {

    @ApiStatus.Internal
    public InventoryPostUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<TransactionScope> scopes
    ) {
        super(inventory, reason, scopes);
    }
}
