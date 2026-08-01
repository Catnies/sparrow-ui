package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Inventory 在事务提交前发出的更新事件.
 * <p>{@link #slotChanges()} 使用当前订阅 Inventory 的槽位坐标,
 * {@link #rootChanges()} 则保留整笔事务涉及的所有 RootInventory 变更.
 */
public final class InventoryPreUpdateEvent extends InventoryUpdateEvent {
    private volatile boolean cancelled; // 是否已经有处理器取消整笔事务

    /**
     * 创建一个提交前更新事件.
     *
     * @param inventory 当前事件使用其逻辑槽位坐标的 Inventory
     * @param reason 事务触发原因
     * @param slotChanges 投影到当前订阅 Inventory 后的槽位变更
     * @param rootChanges 整笔事务的完整 RootInventory 变更
     */
    @ApiStatus.Internal
    public InventoryPreUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges
    ) {
        super(inventory, reason, slotChanges, rootChanges);
    }

    /**
     * 取消整笔事务. 取消是不可逆的, 后执行的处理器不能重新放行.
     */
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * 返回当前取消状态.
     *
     * @return 当前事务是否会被取消
     */
    public boolean cancelled() {
        return this.cancelled;
    }
}
