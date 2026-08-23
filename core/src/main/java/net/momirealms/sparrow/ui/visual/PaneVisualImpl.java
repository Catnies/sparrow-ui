package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class PaneVisualImpl extends AbstractSlotVisual implements PaneVisual {
    private volatile @Nullable ItemProvider background;

    public PaneVisualImpl(@NotNull Bindings bindings, int size) {
        super(bindings, size);
    }

    @Nullable
    @Override
    public ItemProvider background() {
        return this.background;
    }

    @Override
    public void background(@Nullable ItemProvider background) {
        if (this.background == background) {
            return;
        }
        this.background = background;
        this.dirty();
    }
}
