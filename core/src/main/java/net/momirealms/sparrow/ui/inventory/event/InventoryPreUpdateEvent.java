package net.momirealms.sparrow.ui.inventory.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Inventory 在事务提交前发出的更新事件.
 * <p>{@link #slotChanges()} 使用当前订阅 Inventory 的槽位坐标,
 * {@link #rootChanges()} 则保留整笔事务涉及的所有根 Inventory 变化.
 */
public final class InventoryPreUpdateEvent {
    private final UpdateReason reason;                // 整笔事务的触发原因
    private final List<SlotChange> slotChanges;             // 使用订阅视图逻辑坐标的不可变投影
    private final List<RootInventoryChange> rootChanges;   // 使用根坐标的完整不可变事务变更
    private boolean cancelled;                        // 当前处理器最终决定的取消状态

    /**
     * 创建一个提交前更新事件.
     *
     * @param reason 事务触发原因
     * @param slotChanges 当前 Inventory 能看到的槽位变化
     * @param rootChanges 整笔事务在根 Inventory 中的完整变化
     */
    @ApiStatus.Internal
    public InventoryPreUpdateEvent(
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
     * 返回当前订阅 Inventory 能看到的槽位变化.
     *
     * @return 使用当前 Inventory 槽位坐标的变化
     */
    @NotNull
    public List<SlotChange> slotChanges() {
        return this.slotChanges;
    }

    /**
     * 返回整笔事务在所有根 Inventory 中的完整变化.
     *
     * @return 使用根 Inventory 槽位坐标的完整变化
     */
    @NotNull
    public List<RootInventoryChange> rootChanges() {
        return this.rootChanges;
    }

    /**
     * 设置事务是否取消. 后执行的处理器可以覆盖前一个处理器的决定.
     *
     * @param cancelled {@code true} 表示取消事务, {@code false} 表示继续提交
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
