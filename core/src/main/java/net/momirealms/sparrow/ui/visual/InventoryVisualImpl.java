package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.SignalBindings;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class InventoryVisualImpl extends AbstractSlotVisual implements InventoryVisual {

    public InventoryVisualImpl(@NotNull SignalBindings signalBindings, int size) {
        super(signalBindings, size);
    }
}
