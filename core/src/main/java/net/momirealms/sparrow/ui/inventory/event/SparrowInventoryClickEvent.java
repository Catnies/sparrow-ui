package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.click.InteractionEdits;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家点到某个 Inventory 槽位时, 在 Bukkit 点击事件之后, 事务提交前发出的点击事件.
 */
public final class SparrowInventoryClickEvent {
    private final SparrowInventory inventory;  // 被点击的 Inventory
    private final int slot;                    // 被点击的槽位, 用的是这个 Inventory 自己的坐标
    private final Player player;               // 点击的玩家
    private final ClickType clickType;         // 点击类型
    private final int hotbarButton;            // 数字键点击对应的快捷栏索引, 其余点击为 -1
    private final InventoryAction action;      // 点击的 InventoryAction
    private final InteractionEdits edits;      // 把本次点击的写入合并进候选草稿的句柄
    private volatile boolean cancelled;        // 是否被取消

    @ApiStatus.Internal
    public SparrowInventoryClickEvent(
            @NotNull SparrowInventory inventory,
            int slot,
            @NotNull Player player,
            @NotNull ClickType clickType,
            int hotbarButton,
            @NotNull InventoryAction action,
            @NotNull InteractionEdits edits
    ) {
        this.inventory = inventory;
        this.slot = slot;
        this.player = player;
        this.clickType = clickType;
        this.hotbarButton = hotbarButton;
        this.action = action;
        this.edits = edits;
    }

    @NotNull
    public SparrowInventory inventory() {
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
     * 返回把本次点击的写入合并进候选草稿的句柄.
     * <p>与前一道 Bukkit 点击事件用的是同一份. Bukkit 监听器写下的结果就是这里读到的内容,
     * 这里写下的结果又会交给随后的 Pre 处理器. 写入只有在事务真正提交后才生效.
     *
     * @return 本次交互的写入句柄
     */
    @NotNull
    public InteractionEdits edits() {
        return this.edits;
    }

    /**
     * 取消这次 Inventory 点击, 调用之后它不会进入事务规划.
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * 这次点击是否已经被取消.
     *
     * @return 已经被取消时返回 {@code true}
     */
    public boolean cancelled() {
        return this.cancelled;
    }
}
