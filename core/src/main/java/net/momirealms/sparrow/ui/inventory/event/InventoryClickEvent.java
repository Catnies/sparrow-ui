package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.InteractionEdits;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// todo 改名 SparrowInventoryClickEvent
// todo 本事件存在的意义?
/**
 * 玩家点到某个 Inventory 槽位时, 在 Bukkit 点击事件之后, 事务提交前发出的点击事件.
 * <p>只有被 InventoryLink 直接连接的 Inventory 收得到, 因此监听者拿到的槽位就是自己的坐标,
 * 不必关心这个 Window 长什么样. 取消它会让整次点击零变更.
 * <p>想改动这次点击的结果而不是拦掉它, 用 {@link #edits()}: 写进去的内容与前后两道事件共用同一份草稿.
 */
public final class InventoryClickEvent {
    private final SparrowInventory inventory; // 被点击的 Inventory
    private final int slot;                    // 被点击的 Slot
    private final Player player;               // 点击的玩家
    private final ClickType clickType;         // 点击类型
    private final int hotbarButton;            // {@link ClickType#NUMBER_KEY} 对应的快捷栏索引, 未关联快捷栏时为 {@code -1}
    private final InventoryAction action;      // 点击的 InventoryAction
    private final InteractionEdits edits;      // 把本次点击的写入合并进候选草稿的句柄
    private volatile boolean cancelled;        // 是否被取消

    @ApiStatus.Internal
    public InventoryClickEvent(
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
     * <p>与前一道 Bukkit 点击事件用的是同一份: Bukkit 监听器写下的结果就是这里读到的内容,
     * 这里写下的结果又会交给随后的 Pre 处理器. 写入只有在事务真正提交后才生效.
     *
     * @return 本次交互的写入句柄
     */
    @NotNull
    public InteractionEdits edits() {
        return this.edits;
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
