package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 把已映射的协议交互发布为 Bukkit 事件.
 */
final class BukkitInventoryBridge {
    private final BiConsumer<? super String, ? super Throwable> exceptionHandler;

    BukkitInventoryBridge(@NotNull BiConsumer<? super String, ? super Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * 在启用 Bukkit 事件桥接时, 把已映射的协议点击发布为 Bukkit InventoryClickEvent.
     * Bukkit 事件取消或桥接异常都会拒绝该次 Window 点击.
     *
     * @param window 目标 Window
     * @param click 已解释的点击
     * @return 事件未被取消且桥接无异常时为 true
     */
    boolean allowClick(AbstractWindow<?> window, ClickInterpreter.Result.SingleClick click) {
        if (SparrowUI.getInstance().isFireBukkitInventoryEvents()) {
            InventoryView view = window.inventoryView();
            int rawSlot = click.rawSlot();
            InventoryType.SlotType slotType = rawSlot == InventoryView.OUTSIDE
                    ? InventoryType.SlotType.OUTSIDE
                    : view.getSlotType(rawSlot);
            // todo 不能无脑使用InventoryAction.UNKNOWN, 需要引入精确计算, 模拟Paper行为.
            InventoryClickEvent event = new InventoryClickEvent(view, slotType, rawSlot, click.clickType(), InventoryAction.UNKNOWN, click.hotbarButton());
            try {
                Bukkit.getPluginManager().callEvent(event);
                return !event.isCancelled();
            } catch (Throwable throwable) {
                this.exceptionHandler.accept("Failed to bridge Window click to Bukkit", throwable);
                return false;
            }
        }
        return true;
    }

    /**
     * 在启用 Bukkit 事件桥接时, 把已完成的 QUICK_CRAFT 手势发布为 Bukkit InventoryDragEvent.
     * Bukkit 事件取消或桥接异常都会拒绝该次 Window 点击.
     *
     * @param window 目标 Window
     * @param clickType 拖拽手势的点击类型
     * @param slots 手势覆盖的原始槽位
     * @return 事件未被取消且桥接无异常时为 true
     */
    boolean allowDrag(AbstractWindow<?> window, ClickType clickType, List<Integer> slots) {
        if (SparrowUI.getInstance().isFireBukkitInventoryEvents()) {
            InventoryView view = window.inventoryView();
            ItemStack oldCursor = view.getCursor();
            LinkedHashMap<Integer, ItemStack> results = new LinkedHashMap<>();
            for (int index = 0; index < slots.size(); index++) {
                int rawSlot = slots.get(index);
                ItemStack current = view.getItem(rawSlot);
                results.put(rawSlot, current == null ? ItemStack.empty() : current);
            }
            InventoryDragEvent event = new InventoryDragEvent(view, oldCursor.clone(), oldCursor, clickType == ClickType.RIGHT, results);
            try {
                Bukkit.getPluginManager().callEvent(event);
                return !event.isCancelled();
            } catch (Throwable throwable) {
                this.exceptionHandler.accept("Failed to bridge Window drag to Bukkit", throwable);
                return false;
            }
        }
        return true;
    }
}
