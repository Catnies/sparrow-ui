package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 在 Window 中的 Inventory 槽里选择 Bundle 内部物品时派发的事件.
 *
 * @param inventory 包含 Bundle 的 SparrowInventory
 * @param inventorySlot Bundle 所在的当前 Inventory 逻辑槽位
 * @param player 发起选择的玩家
 * @param window 发起选择的 Window
 * @param windowSlot Bundle 所在的 Window 协议槽位(raw slot)
 * @param bundleSlot Bundle内部槽位; {@code -1} 表示光标已离开
 */
public record InventoryBundleSelectEvent(
        @NotNull SparrowInventory inventory,
        int inventorySlot,
        @NotNull Player player,
        @NotNull Window window,
        int windowSlot,
        int bundleSlot
) {
}
