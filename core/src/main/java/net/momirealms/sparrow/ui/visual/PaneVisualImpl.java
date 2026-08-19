package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class PaneVisualImpl extends AbstractSlotVisual implements PaneVisual {
    // 背景与两层映射之间没有不变量, 各自独立发布即可, 不必与它们同一次替换
    private volatile @Nullable ItemProvider background;

    public PaneVisualImpl(@NotNull SignalBindings signalBindings, int size) {
        super(signalBindings, size);
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
