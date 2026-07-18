package net.momirealms.sparrow.ui;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

/**
 * 一次 Item 点击的最小只读上下文.
 *
 * @param clickType Bukkit 解析后的点击类型
 * @param player 执行点击的玩家
 */
public record ItemClick (
        @NotNull ClickType clickType,
        @NotNull Player player,
        @NotNull Window window,
        int windowSlot,
        int hotbarButton
) {

    /**
     * 根据点击情况创建一个新的 {@link ItemClick}.
     */
    public ItemClick(Player player, ClickType clickType, Window window, int windowSlot) {
        this(clickType, player, window, windowSlot, -1);
    }
}
