package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Bindings;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class WindowVisualImpl extends AbstractSlotVisual implements WindowVisual {

    public WindowVisualImpl(@NotNull Bindings bindings, int size) {
        super(bindings, size);
    }
}
