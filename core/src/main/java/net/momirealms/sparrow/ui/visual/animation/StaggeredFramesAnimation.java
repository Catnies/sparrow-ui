package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 逐槽错峰的帧序列, 每槽未轮到时显示 pendingCover, 轮到后按周期走帧, 走完放行.
// 帧序列为空即"轮到就放行" (逐格出现), 错峰为零即全槽同步.
record StaggeredFramesAnimation(
        int @NotNull [] slots,
        long periodTicks,
        long staggerTicks,
        @NotNull ImmediateItemProvider @NotNull [] frames,
        @Nullable ImmediateItemProvider pendingCover,
        long totalTicks
) implements AnimationDefinition {
    static final ImmediateItemProvider[] NO_FRAMES = new ImmediateItemProvider[0];

    @Nullable
    @Override
    public ItemProvider frame(int orderIndex, int slot, long elapsedTicks, @Nullable ItemStack actual) {
        long localTick = elapsedTicks - this.staggerTicks * orderIndex;
        if (localTick < 0) {
            return this.pendingCover;
        }
        long index = localTick / this.periodTicks;
        return index < this.frames.length ? this.frames[(int) index] : null;
    }
}
