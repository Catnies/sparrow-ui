package net.momirealms.sparrow.ui.click;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

/**
 * 一次物品点击的上下文.
 *
 * @param clickType 点击类型
 * @param player 点击玩家
 * @param window 当前 Window
 * @param windowSlot 点击的 Window 槽位
 * @param hotbarButton {@link ClickType#NUMBER_KEY} 对应的快捷栏索引, 未关联快捷栏时为 {@code -1}
 */
public record ItemClick (
        @NotNull ClickType clickType,
        @NotNull Player player,
        @NotNull Window window,
        int windowSlot,
        int hotbarButton
) {

    public ItemClick(Player player, ClickType clickType, Window window, int windowSlot) {
        this(clickType, player, window, windowSlot, -1);
    }
}
