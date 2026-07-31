package net.momirealms.sparrow.ui.inventory.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class InventoryPostUpdateEvent {
    private final UpdateReason reason;
    private final List<InventoryDelta> changes;

    @ApiStatus.Internal
    public InventoryPostUpdateEvent(@NotNull UpdateReason reason, @NotNull List<InventoryDelta> changes) {
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
}
