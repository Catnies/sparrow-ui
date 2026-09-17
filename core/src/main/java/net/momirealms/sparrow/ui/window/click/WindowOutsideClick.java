package net.momirealms.sparrow.ui.window.click;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 容器外点击处理器收到的那份可以取消的事件.
 */
public final class WindowOutsideClick {
    private final Player player;
    private final Window window;
    private final ClickType clickType;
    private final ItemStack cursor;
    private final int hotbarButton;
    private boolean cancelled;

    /**
     * 建一次容器外点击事件.
     *
     * @param player 发起点击的玩家
     * @param window 当前 Window
     * @param clickType Bukkit 那边的点击类型
     * @param hotbarButton {@link ClickType#NUMBER_KEY} 对应的快捷栏索引, 未关联快捷栏时为 {@code -1}
     * @param cursor 派发这一刻菜单实际光标的快照
     */
    public WindowOutsideClick(@NotNull Player player, @NotNull Window window, @NotNull ClickType clickType, @NotNull ItemStack cursor, int hotbarButton) {
        this.player = player;
        this.window = window;
        this.clickType = clickType;
        this.hotbarButton = hotbarButton;
        this.cursor = cursor.clone(); // 光标当场复制一份, 事件里这份跟菜单之后怎么变没关系
    }

    public @NotNull Player getPlayer() {
        return this.player;
    }

    @NotNull
    public Window getWindow() {
        return this.window;
    }

    public @NotNull ClickType getClickType() {
        return this.clickType;
    }

    public int getHotbarButton() {
        return this.hotbarButton;
    }

    @NotNull
    public ItemStack getCursor() {
        return this.cursor;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
