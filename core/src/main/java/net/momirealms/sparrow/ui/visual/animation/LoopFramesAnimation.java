package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 帧序列取模无限循环, 只能被取消或随宿主关闭而结束.
record LoopFramesAnimation(
        int @NotNull [] slots,
        long periodTicks,
        @NotNull ImmediateItemProvider @NotNull [] frames
) implements AnimationDefinition {

    @Override
    public long totalTicks() {
        return -1L;
    }

    @Override
    public ItemProvider frame(int orderIndex, int slot, long elapsedTicks, @Nullable ItemStack actual) {
        return this.frames[(int) (elapsedTicks / this.periodTicks % this.frames.length)];
    }
}
