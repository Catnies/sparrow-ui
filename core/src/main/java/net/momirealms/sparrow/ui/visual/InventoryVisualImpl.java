package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class InventoryVisualImpl extends AbstractSlotVisual implements InventoryVisual {
    private volatile @Nullable ItemProvider background;

    public InventoryVisualImpl(@NotNull Bindings bindings, int size) {
        super(bindings, size);
    }

    @Nullable
    @Override
    public ItemProvider background() {
        return this.background;
    }

    @Override
    public void background(@Nullable ItemProvider background) {
        // 同一个实例就不标脏, 免得白走一轮
        if (this.background != background) {
            this.background = background;
            this.dirty();
        }
    }

    @Nullable
    @Override
    public ResolvedVisual visualizeWithBackground(int slot, @Nullable ItemStack actual) {
        // 映射都放行之后才轮到背景, 而且只有这一格真的是空的才用, 非空槽按真实内容显示
        ResolvedVisual bound = this.visualize(slot, actual);
        if (bound != null) {
            return bound;
        }
        ItemProvider background = this.background;
        return actual == null && background != null ? ResolvedVisual.of(background) : null;
    }
}
