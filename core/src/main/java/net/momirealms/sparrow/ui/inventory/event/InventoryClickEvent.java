package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.Inventory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

public final class InventoryClickEvent {
    private final Inventory inventory;      // 被点击的 Inventory
    private final int slot;                 // 被点击的 Slot
    private final Player player;            // 点击的玩家
    private final ClickType clickType;      // 点击类型
    private final int hotbarButton;         // {@link ClickType#NUMBER_KEY} 对应的快捷栏索引, 未关联快捷栏时为 {@code -1}
    private final InventoryAction action;   // 点击的 InventoryAction
    private volatile boolean cancelled;     // 是否被取消

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
