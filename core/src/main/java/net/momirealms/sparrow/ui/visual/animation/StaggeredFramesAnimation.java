package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 逐格错峰的帧序列: 还没轮到的显示 pendingCover, 轮到后按周期走帧, 走完放行.
// frames 为空的那个形态就是逐格出现; staggerTicks 为 0 就是所有格同步.
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
        // 还没轮到自己这一格起播
        if (localTick < 0) {
            return this.pendingCover;
        }
        long index = localTick / this.periodTicks;
        // 帧走完了就放行, 让更早开始的播放或下面的配置层露出来
        return index < this.frames.length ? this.frames[(int) index] : null;
    }
}
