package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Inventory 在事务提交后发出的更新事件.
 */
public final class InventoryPostUpdateEvent extends InventoryUpdateEvent {
    private final long version;

    @ApiStatus.Internal
    public InventoryPostUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<TransactionScope> scopes,
            long version
    ) {
        super(inventory, reason, scopes);
        this.version = version;
    }

    /**
     * 返回当前事务的版本.
     * <p>同一跨 Inventory 事务产生的所有 Post 事件共享同一个版本.
     * 不同事务的 Post 可能并发或乱序到达, 可用本值判断新旧.
     *
     * @return 当前事务的版本
     */
    public long version() {
        return this.version;
    }
}
