package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

record FrameFunctionAnimation(
        int @NotNull [] slots,
        long periodTicks,
        long totalTicks,
        @NotNull FrameFunction frameFunction
) implements AnimationDefinition {

    @Nullable
    @Override
    public ItemProvider frame(int orderIndex, int slot, long elapsedTicks, @Nullable ItemStack actual) {
        return this.frameFunction.frame(orderIndex, slot, elapsedTicks, actual);
    }
}
