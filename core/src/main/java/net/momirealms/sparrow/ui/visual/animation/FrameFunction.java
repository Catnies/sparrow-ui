package net.momirealms.sparrow.ui.visual.animation;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 按槽位和已播放时间计算一帧物品.
 */
@FunctionalInterface
public interface FrameFunction {

    /**
     * 求值一个参与槽位此刻的显示, 契约见 {@link AnimationDefinition#frame}.
     *
     * @param orderIndex 槽位在动画中的序号
     * @param slot 宿主槽位
     * @param elapsedTicks 从播放开始经过的 tick 数
     * @param actual 该显示位的同步可读内容, 没有时为 {@code null}, <strong>只读, 不得修改</strong>
     * @return 此刻的帧, {@code null} 表示这一槽此刻放行
     */
    @Nullable
    ItemProvider frame(int orderIndex, int slot, long elapsedTicks, @Nullable ItemStack actual);
}
