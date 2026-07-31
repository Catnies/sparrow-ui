package net.momirealms.sparrow.ui.inventory.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class InventoryPreUpdateEvent {
    private final UpdateReason reason;
    private final List<InventoryDelta> changes;
    private volatile boolean cancelled; // volatile 兜底跨线程误用时的可见性, 正常路径只在派发线程翻转

    @ApiStatus.Internal
    public InventoryPreUpdateEvent(@NotNull UpdateReason reason, @NotNull List<InventoryDelta> changes) {
        this.reason = reason;
        this.changes = changes;
    }

    @NotNull
    public UpdateReason reason() {
        return this.reason;
    }

    @NotNull
    public List<InventoryDelta> changes() {
        return this.changes;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public boolean cancelled() {
        return this.cancelled;
    }
}
