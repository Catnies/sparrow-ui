package net.momirealms.sparrow.ui.click;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public final class WindowOutsideClick {
    private final Player player;
    private final Window window;
    private final ClickType clickType;
    private final ItemStack cursor;
    private final int hotbarButton;
    private boolean cancelled;

    /**
     * Window 外部点击处理器接收的可取消事件.
     *
     * @param player 发起点击的玩家
     * @param window 当前 Window
     * @param clickType Bukkit 点击类型
     * @param hotbarButton {@link ClickType#NUMBER_KEY} 对应的快捷栏索引, 未关联快捷栏时为 {@code -1}
     * @param cursor 派发时菜单持有的权威光标快照
     */
    public WindowOutsideClick(@NotNull Player player, @NotNull Window window, @NotNull ClickType clickType, @NotNull ItemStack cursor, int hotbarButton) {
        this.player = player;
        this.window = window;
        this.clickType = clickType;
        this.hotbarButton = hotbarButton;
        this.cursor = cursor.clone();
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
