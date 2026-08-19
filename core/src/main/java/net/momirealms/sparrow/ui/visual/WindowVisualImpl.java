package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.SignalBindings;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class WindowVisualImpl extends AbstractSlotVisual implements WindowVisual {

    public WindowVisualImpl(@NotNull SignalBindings signalBindings, int size) {
        super(signalBindings, size);
    }
}
