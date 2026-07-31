package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.Inventory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 在 Window 中的 Inventory 槽里选择 Bundle 内部物品时派发的事件.
 *
 * @param inventory 包含Bundle的逻辑Inventory
 * @param slot Bundle所在的逻辑槽
 * @param player 发起选择的玩家
 * @param bundleSlot Bundle内部槽位; {@code -1} 表示光标已离开
 */
public record InventoryBundleSelectEvent(
        @NotNull Inventory inventory,
        int slot,
        @NotNull Player player,
        int bundleSlot
) {
}
