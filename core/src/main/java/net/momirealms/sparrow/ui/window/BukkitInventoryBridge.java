package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.inventory.event.InventoryClickAction;
import net.momirealms.sparrow.ui.inventory.click.InteractionEdits;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import net.momirealms.sparrow.ui.SparrowUI;

import java.util.Map;

// 把 Sparrow 解释好的点击包成 Bukkit 的 InventoryClickEvent 与 InventoryDragEvent 发出去, 再把监听器的取消和写入带回来.
final class BukkitInventoryBridge {

    BukkitInventoryBridge() {
    }

    /**
     * 把解释好的单击发成 Bukkit 的 InventoryClickEvent.
     * <p>事件被取消, 或者桥接路上抛了异常, 这次点击都不放行; 异常报给插件日志.
     *
     * @param window 目标 Window
     * @param click 已解释的点击
     * @param action 按当前 Window 只读状态估出来的操作
     * @return 事件没被取消, 桥接也没出事时为 true
     */
    boolean allowClick(@NotNull AbstractWindow<?> window, @NotNull ClickInterpreter.Result.SingleClick click, @NotNull InventoryClickAction action) {
        int rawSlot = click.rawSlot();
        InventoryView view = window.inventoryView();
        InventoryType.SlotType slotType = rawSlot == InventoryView.OUTSIDE
                ? InventoryType.SlotType.OUTSIDE
                : view.getSlotType(rawSlot);
        InventoryClickEvent event = new InventoryClickEvent(view, slotType, rawSlot, click.clickType(), InventoryClickActionAdapter.toBukkit(action), click.hotbarButton());
        try {
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        } catch (Throwable throwable) {
            SparrowUI.getInstance().handleException("Failed to bridge Window click to Bukkit", throwable);
            return false;
        }
    }

    /**
     * 把一次已经算完的 QUICK_CRAFT 手势发成 Bukkit 的 InventoryDragEvent.
     * <p>取消和异常同样不放行. 监听器在事件里改过的光标会记进候选草稿, 后面的复核才知道这一笔.
     *
     * @param window 目标 Window
     * @param clickType 拖拽手势的点击类型
     * @param newCursor 候选提交后的光标物品
     * @param newItems 候选提交后的协议槽位内容
     * @param edits 把事件写入合并进本次候选草稿的句柄
     * @return 事件没被取消, 桥接也没出事时为 true
     */
    boolean allowDrag(AbstractWindow<?> window, ClickType clickType, ItemStack newCursor, Map<Integer, ItemStack> newItems, InteractionEdits edits) {
        InventoryView view = window.inventoryView();
        ItemStack oldCursor = view.getCursor();
        InventoryDragEvent event = new InventoryDragEvent(view, newCursor, oldCursor, clickType == ClickType.RIGHT, newItems);
        try {
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            // setCursor 只是写事件字段, 跟构造值不一样才算监听器真的改过光标;
            // 把没动过的值也记进草稿, 候选复核时会白白作废.
            if (!ItemUtils.isContentEqual(newCursor, event.getCursor())) {
                edits.cursor(event.getCursor());
            }
            return true;
        } catch (Throwable throwable) {
            SparrowUI.getInstance().handleException("Failed to bridge Window drag to Bukkit", throwable);
            return false;
        }
    }
}
