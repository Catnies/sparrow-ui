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
    private final int[] orderBySlot; // 宿主槽位 -> 动画里的第几格, -1 表示这一格不参与
    final int[] slots;               // 这次播放覆盖的宿主槽位, 到点换帧时按它标脏

    public ActiveSlotAnimation(@NotNull AbstractSlotVisual host, @NotNull AnimationDefinition animationDefinition, int @NotNull [] slots, int @NotNull [] orderBySlot, long startTick) {
        super(host, startTick, animationDefinition.totalTicks());
        this.animationDefinition = animationDefinition;
        this.slots = slots;
        this.orderBySlot = orderBySlot;
    }

    // 向动画描述要这一格此刻的帧; 这一格不参与, 或者帧放行, 就给 null.
    @Nullable
    ResolvedVisual visualize(int slot, @Nullable ItemStack actual, long nowTick) {
        int orderIndex = this.orderBySlot[slot];
        if (orderIndex < 0) return null;
        ItemProvider frame = this.animationDefinition.frame(orderIndex, slot, nowTick - this.startTick(), actual);
        // 来源身份用这次播放自己, 播放期间不变, 所以换帧只是一次重求值, 不算换了来源
        return frame == null ? null : new ResolvedVisual(this, frame, null);
    }

    // 到点换帧: 把这次播放覆盖的槽位都标脏, 渲染层自然会来重求值
    @Override
    protected void advanceFrame(@NotNull AbstractSlotVisual host) {
        host.dirtyAnimated(this.slots);
    }

    // 播放结束或被取消: 把自己从宿主上摘掉, 下面的配置层重新露出来
    @Override
    protected void detach(@NotNull AbstractSlotVisual host) {
        host.removeAnimation(this);
    }
}
