package net.momirealms.sparrow.ui.item.click;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 一次物品点击的上下文.
 *
 * @param clickType 点击类型
 * @param player 点击玩家
 * @param window 所属 Window
 * @param cursor 派发时菜单持有的光标快照, 构造时复制
 * @param windowSlot 点击的 Window 槽位
 * @param hotbarButton {@link ClickType#NUMBER_KEY} 对应的快捷栏索引, 未关联快捷栏时为 {@code -1}
 */
public record ItemClick (
        @NotNull ClickType clickType,
        @NotNull Player player,
        @NotNull Window window,
        @NotNull ItemStack cursor,
        int windowSlot,
        int hotbarButton
) implements ItemInteraction {

    public ItemClick {
        cursor = cursor.clone();
    }

    /**
     * 创建不关联快捷栏按键的点击上下文.
     *
     * @param player 点击玩家
     * @param clickType 点击类型
     * @param window 所属 Window
     * @param cursor 派发时菜单持有的光标快照, 构造时复制
     * @param windowSlot 点击的 Window 槽位
     */
    public ItemClick(@NotNull Player player, @NotNull ClickType clickType, @NotNull Window window, @NotNull ItemStack cursor, int windowSlot) {
        this(clickType, player, window, cursor, windowSlot, -1);
    }
}
