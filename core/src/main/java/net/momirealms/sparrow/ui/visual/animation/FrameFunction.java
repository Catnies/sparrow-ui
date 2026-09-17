package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 给定槽位和已经播过的 tick 数, 算出此刻盖在这一格上的物品.
 */
@FunctionalInterface
public interface FrameFunction {

    /**
     * 算出一格此刻的帧, 纯性与开销的约定见 {@link AnimationDefinition#frame}.
     *
     * @param orderIndex 这一格在动画里的序号
     * @param slot 宿主槽位
     * @param elapsedTicks 从播放开始经过的 tick 数
     * @param actual 该显示位的同步可读内容, 没有时为 {@code null}, <strong>只读, 不得修改</strong>
     * @return 此刻的帧; 这一格此刻放行时给 {@code null}
     */
    @Nullable
    ItemProvider frame(int orderIndex, int slot, long elapsedTicks, @Nullable ItemStack actual);
}
