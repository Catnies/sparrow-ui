package net.momirealms.sparrow.ui.window.click;

import net.momirealms.sparrow.ui.window.EnchantmentWindow;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家点了附魔台按钮时的上下文.
 *
 * @param player 点按钮的玩家
 * @param window 按钮所属的那扇窗
 * @param index 按钮索引
 * @param option 收到这个包时按钮上的选项快照
 */
public record EnchantSelectClick(
        @NotNull Player player,
        @NotNull EnchantmentWindow window,
        int index,
        @NotNull EnchantmentWindow.EnchantOption option
) {
}
