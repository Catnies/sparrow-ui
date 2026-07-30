package net.momirealms.sparrow.ui;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家在 Item 显示的 Bundle 内容中选择槽位的上下文.
 *
 * @param player 执行选择的玩家
 * @param bundleSlot Bundle 内槽位; {@code -1} 表示光标已离开.
 */
public record BundleSelectClick(@NotNull Player player, int bundleSlot) {

    public BundleSelectClick {
        if (bundleSlot < -1) {
            throw new IllegalArgumentException("bundleSlot must be -1 or non-negative");
        }
    }
}
