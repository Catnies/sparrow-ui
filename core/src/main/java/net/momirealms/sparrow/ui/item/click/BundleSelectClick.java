package net.momirealms.sparrow.ui.item.click;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 一次 Bundle 内容槽位选择的上下文.
 *
 * @param player 执行选择的玩家
 * @param window 所属 Window
 * @param windowSlot Bundle 所在的 Window 槽位
 * @param bundleSlot Bundle 内槽位, {@code -1} 表示未选中任何槽位
 */
public record BundleSelectClick(
        @NotNull Player player,
        @NotNull Window window,
        int windowSlot,
        int bundleSlot
) implements ItemInteraction {

    public BundleSelectClick {
        if (bundleSlot < -1) {
            throw new IllegalArgumentException("bundleSlot must be -1 or non-negative");
        }
    }
}
