package net.momirealms.sparrow.ui.inventory.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Inventory 在事务提交后发出的更新事件.
 * <p>{@link #slotChanges()} 是投影到当前订阅 Inventory 后的槽位变更,
 * {@link #rootChanges()} 保留整笔事务涉及的所有 RootInventory 变更.
 */
public final class InventoryPostUpdateEvent {
    private final UpdateReason reason;                // 整笔事务的触发原因
    private final List<SlotChange> slotChanges;             // 投影到当前订阅 Inventory 后的槽位变更
    private final List<RootInventoryChange> rootChanges;    // 整笔事务的完整 RootInventory 变更

    @ApiStatus.Internal
    public InventoryPostUpdateEvent(
            @NotNull UpdateReason reason,
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges
    ) {
        this.reason = reason;
        this.slotChanges = slotChanges;
        this.rootChanges = rootChanges;
    }

    /**
     * 返回本次事务的触发原因.
     *
     * @return 事务触发原因
     */
    @NotNull
    public UpdateReason reason() {
        return this.reason;
    }

    /**
     * 返回投影到当前订阅 Inventory 后的槽位变更.
     *
     * @return 使用当前 Inventory 槽位坐标的变更记录
     */
    @NotNull
    public List<SlotChange> slotChanges() {
        return this.slotChanges;
    }

    /**
     * 返回整笔事务涉及的所有 RootInventory 变更组.
     *
     * @return 使用 RootInventory 槽位坐标的完整事务变更
     */
    @NotNull
    public List<RootInventoryChange> rootChanges() {
        return this.rootChanges;
    }
}
