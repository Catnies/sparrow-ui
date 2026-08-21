package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.inventory.InteractionEdits;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import net.momirealms.sparrow.ui.SparrowUI;

import java.util.Map;

final class BukkitInventoryBridge {

    BukkitInventoryBridge() {
    }

    /**
     * 把已映射的协议点击发布为 Bukkit InventoryClickEvent.
     * Bukkit 事件取消或桥接异常都会拒绝该次 Window 点击.
     *
     * @param window 目标 Window
     * @param click 已解释的点击
     * @param action 基于当前 Window 只读状态估计出的操作
     * @return 事件未被取消且桥接无异常时为 true
     */
    boolean allowClick(@NotNull AbstractWindow<?> window, @NotNull ClickInterpreter.Result.SingleClick click, @NotNull InventoryAction action) {
        int rawSlot = click.rawSlot();
        InventoryView view = window.inventoryView();
        InventoryType.SlotType slotType = rawSlot == InventoryView.OUTSIDE
                ? InventoryType.SlotType.OUTSIDE
                : view.getSlotType(rawSlot);
        InventoryClickEvent event = new InventoryClickEvent(view, slotType, rawSlot, click.clickType(), action, click.hotbarButton());
        try {
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        } catch (Throwable throwable) {
            SparrowUI.getInstance().handleException("Failed to bridge Window click to Bukkit", throwable);
            return false;
        }
    }

    /**
     * 把已完成的 QUICK_CRAFT 手势发布为 Bukkit InventoryDragEvent.
     * Bukkit 事件取消或桥接异常都会拒绝该次 Window 点击.
     *
     * @param window 目标 Window
     * @param clickType 拖拽手势的点击类型
     * @param newCursor 候选提交后的光标物品
     * @param newItems 候选提交后的协议槽位内容
     * @param edits 把事件写入合并进本次候选草稿的句柄
     * @return 事件未被取消且桥接无异常时为 true
     */
    boolean allowDrag(AbstractWindow<?> window, ClickType clickType, ItemStack newCursor, Map<Integer, ItemStack> newItems, InteractionEdits edits) {
        InventoryView view = window.inventoryView();
        ItemStack oldCursor = view.getCursor();
        InventoryDragEvent event = new InventoryDragEvent(view, newCursor, oldCursor, clickType == ClickType.RIGHT, newItems);
        try {
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            // 拖拽事件的 setCursor 写的是事件自己的字段而不是 InventoryView, 在这里回传.
            // 事件构造时用的就是这份 newCursor, 值没变说明没人调过 setCursor —— 不写, 否则每次拖拽
            // 都会被记成一次监听器写入, 候选作废后就再也不会重规划.
            if (!newCursor.equals(event.getCursor())) {
                edits.cursor(event.getCursor());
            }
            return true;
        } catch (Throwable throwable) {
            SparrowUI.getInstance().handleException("Failed to bridge Window drag to Bukkit", throwable);
            return false;
        }
    }
}
