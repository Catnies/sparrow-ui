package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.Inventory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家点击Window中的Inventory连接槽时派发的独立事件.
 * <p>本事件先于事务规划, 因此没有槽位变化的点击同样会派发.
 */
public final class InventoryClickEvent {
    private final Inventory inventory;
    private final int slot;
    private final Player player;
    private final ClickType clickType;
    private final int hotbarButton;
    private final InventoryAction action;
    private volatile boolean cancelled;

    @ApiStatus.Internal
    public InventoryClickEvent(
            @NotNull Inventory inventory,
            int slot,
            @NotNull Player player,
            @NotNull ClickType clickType,
            int hotbarButton,
            @NotNull InventoryAction action
    ) {
        this.inventory = inventory;
        this.slot = slot;
        this.player = player;
        this.clickType = clickType;
        this.hotbarButton = hotbarButton;
        this.action = action;
    }

    @NotNull
    public Inventory inventory() {
        return this.inventory;
    }

    public int slot() {
        return this.slot;
    }

    @NotNull
    public Player player() {
        return this.player;
    }

    @NotNull
    public ClickType clickType() {
        return this.clickType;
    }

    public int hotbarButton() {
        return this.hotbarButton;
    }

    @NotNull
    public InventoryAction action() {
        return this.action;
    }

    /**
     * 取消这次Inventory点击. 调用后不会进入事务规划.
     */
    public void cancel() {
        this.cancelled = true;
    }

    public boolean cancelled() {
        return this.cancelled;
    }
}
