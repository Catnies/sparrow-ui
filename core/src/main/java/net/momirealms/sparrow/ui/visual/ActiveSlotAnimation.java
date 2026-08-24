package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.visual.animation.ActivePlayback;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class ActiveSlotAnimation extends ActivePlayback<AbstractSlotVisual> {
    static final ActiveSlotAnimation[] NONE = new ActiveSlotAnimation[0];

    private final AnimationDefinition animationDefinition;
    private final int[] orderBySlot; // 宿主槽位 -> orderIndex, -1 表示不参与
    final int[] slots;

    public ActiveSlotAnimation(@NotNull AbstractSlotVisual host, @NotNull AnimationDefinition animationDefinition, int @NotNull [] slots, int @NotNull [] orderBySlot, long startTick) {
        super(host, startTick, animationDefinition.totalTicks());
        this.animationDefinition = animationDefinition;
        this.slots = slots;
        this.orderBySlot = orderBySlot;
    }

    // 求值一个槽位此刻的显示, 槽位不参与或帧放行时返回 null.
    @Nullable
    ResolvedVisual visualize(int slot, @Nullable ItemStack actual, long nowTick) {
        int orderIndex = this.orderBySlot[slot];
        if (orderIndex < 0) return null;
        ItemProvider frame = this.animationDefinition.frame(orderIndex, slot, nowTick - this.startTick(), actual);
        return frame == null ? null : new ResolvedVisual(this, frame, null);
    }

    @Override
    protected void advanceFrame(@NotNull AbstractSlotVisual host) {
        host.dirtyAnimated(this.slots);
    }

    @Override
    protected void detach(@NotNull AbstractSlotVisual host) {
        host.removeAnimation(this);
    }
}
