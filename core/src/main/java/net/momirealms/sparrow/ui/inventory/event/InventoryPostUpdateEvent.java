package net.momirealms.sparrow.ui.inventory.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class InventoryPostUpdateEvent {
    private final UpdateReason reason;                // 整笔事务的触发原因
    private final List<SlotChange> slotChanges;             // 使用订阅视图逻辑坐标的不可变投影
    private final List<RootInventoryChange> rootChanges;   // 使用根坐标的完整不可变事务变更

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

    @NotNull
    public UpdateReason reason() {
        return this.reason;
    }

    @NotNull
    public List<SlotChange> slotChanges() {
        return this.slotChanges;
    }

    @NotNull
    public List<RootInventoryChange> rootChanges() {
        return this.rootChanges;
    }
}
