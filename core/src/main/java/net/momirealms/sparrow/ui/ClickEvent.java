package net.momirealms.sparrow.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

/**
 * Window 外部点击处理器接收的可取消事件.
 *
 * <p>处理器可调用 {@link #setCancelled(boolean)} 拒绝本次点击; Window 会在全部处理器执行完毕后读取取消状态.</p>
 */
public final class ClickEvent {
    private final Player player;
    private final ClickType clickType;
    private final int hotbarButton;
    private boolean cancelled;

    /**
     * 创建一次已映射的点击事件.
     *
     * @param player 发起点击的玩家
     * @param clickType Bukkit 点击类型
     * @param hotbarButton {@link ClickType#NUMBER_KEY} 对应的快捷栏索引, 未关联快捷栏时为 {@code -1}
     */
    public ClickEvent(@NotNull Player player, @NotNull ClickType clickType, int hotbarButton) {
        this.player = player;
        this.clickType = clickType;
        this.hotbarButton = hotbarButton;
    }

    public @NotNull Player getPlayer() {
        return this.player;
    }

    public @NotNull ClickType getClickType() {
        return this.clickType;
    }

    public int getHotbarButton() {
        return this.hotbarButton;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
