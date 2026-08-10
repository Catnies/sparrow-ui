package net.momirealms.sparrow.ui.window.click;

import net.momirealms.sparrow.ui.window.EnchantmentWindow;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家选择附魔台协议按钮时产生的点击上下文.
 *
 * @param player 选择按钮的玩家
 * @param window 所属 EnchantmentWindow
 * @param index 选择的按钮索引
 * @param option 收到按钮包时的选项快照
 */
public record EnchantSelectClick(
        @NotNull Player player,
        @NotNull EnchantmentWindow window,
        int index,
        @NotNull EnchantmentWindow.EnchantOption option
) {
}
