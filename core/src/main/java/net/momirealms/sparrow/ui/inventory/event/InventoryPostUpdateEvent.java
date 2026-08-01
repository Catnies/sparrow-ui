package net.momirealms.sparrow.ui.inventory.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class InventoryPostUpdateEvent {
    private final UpdateReason reason;                // 整笔事务的触发原因
    private final List<SlotDelta> deltas;             // 使用订阅视图逻辑坐标的不可变投影
    private final List<InventoryDelta> rootChanges;   // 使用根坐标的完整不可变事务变更

    @ApiStatus.Internal
    public InventoryPostUpdateEvent(
            @NotNull UpdateReason reason,
            @NotNull List<SlotDelta> deltas,
            @NotNull List<InventoryDelta> rootChanges
    ) {
        this.reason = reason;
        this.deltas = deltas;
        this.rootChanges = rootChanges;
    }

    @NotNull
    public UpdateReason reason() {
        return this.reason;
    }

    @NotNull
    public List<SlotDelta> deltas() {
        return this.deltas;
    }

    @NotNull
    public List<InventoryDelta> rootChanges() {
        return this.rootChanges;
    }
}
