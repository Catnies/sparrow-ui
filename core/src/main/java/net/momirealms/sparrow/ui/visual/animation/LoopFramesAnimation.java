package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 帧序列取模循环, 永远播不完, 只能取消或者等宿主关闭.
record LoopFramesAnimation(
        int @NotNull [] slots,
        long periodTicks,
        @NotNull ImmediateItemProvider @NotNull [] frames
) implements AnimationDefinition {

    @Override
    public long totalTicks() {
        // 无限时长, 对外报 -1
        return -1L;
    }

    @Override
    public ItemProvider frame(int orderIndex, int slot, long elapsedTicks, @Nullable ItemStack actual) {
        return this.frames[(int) (elapsedTicks / this.periodTicks % this.frames.length)];
    }
}
