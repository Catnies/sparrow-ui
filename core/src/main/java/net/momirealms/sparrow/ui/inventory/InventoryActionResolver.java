package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentHolderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

final class InventoryActionResolver {

    private InventoryActionResolver() {
    }

    // 根据当前点击 Context 推导出正确的 Paper InventoryAction.
    @NotNull
    static InventoryAction resolve(
            @NotNull ClickSemantics.Context context,
            @NotNull ClickType clickType,
            int hotbarButton,
            int windowSlot
    ) {
        ItemStack cursor = context.cursor();
        if (windowSlot == InventoryView.OUTSIDE) {
            return estimateOutsideInventoryAction(cursor, clickType);
        }
        if (clickType == ClickType.UNKNOWN || clickType == ClickType.CREATIVE) {
            return InventoryAction.UNKNOWN;
        }

        ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
        if (link == null) {
            return clickType == ClickType.DOUBLE_CLICK
                    ? estimateCollectToCursor(context, cursor)
                    : InventoryAction.NOTHING;
        }
        if (context.frozenAt(windowSlot)) {
            return InventoryAction.NOTHING;
        }

        @Nullable ItemStack current = link.inventory().itemAt(link.slot());
        return switch (clickType) {
            case LEFT -> estimateLeftClick(link, cursor, current);
            case RIGHT -> estimateRightClick(link, cursor, current);
            case SHIFT_LEFT, SHIFT_RIGHT -> current == null ? InventoryAction.NOTHING : InventoryAction.MOVE_TO_OTHER_INVENTORY;
            case NUMBER_KEY -> estimateHotbarSwap(context, link, current, hotbarButton);
            case SWAP_OFFHAND -> current == null && ItemUtils.isNullOrEmpty(context.offhand())
                    ? InventoryAction.NOTHING
                    : InventoryAction.HOTBAR_SWAP;
            case DROP -> cursor.isEmpty() && current != null ? InventoryAction.DROP_ONE_SLOT : InventoryAction.NOTHING;
            case CONTROL_DROP -> cursor.isEmpty() && current != null ? InventoryAction.DROP_ALL_SLOT : InventoryAction.NOTHING;
            case DOUBLE_CLICK -> current == null ? estimateCollectToCursor(context, cursor) : InventoryAction.NOTHING;
            case MIDDLE -> context.viewer().getGameMode() == GameMode.CREATIVE && cursor.isEmpty() && current != null
                    ? InventoryAction.CLONE_STACK
                    : InventoryAction.NOTHING;
            case WINDOW_BORDER_LEFT, WINDOW_BORDER_RIGHT -> InventoryAction.NOTHING;
            case UNKNOWN, CREATIVE -> InventoryAction.UNKNOWN;
        };
    }

    // 预估点在窗口外的操作: 光标上有东西时左键丢整堆, 右键丢一个.
    @NotNull
    private static InventoryAction estimateOutsideInventoryAction(
            ItemStack cursor,
            ClickType clickType
    ) {
        if (clickType == ClickType.UNKNOWN || clickType == ClickType.CREATIVE) {
            return InventoryAction.UNKNOWN;
        }
        if (cursor.isEmpty()) {
            return InventoryAction.NOTHING;
        }
        return switch (clickType) {
            case LEFT, WINDOW_BORDER_LEFT -> InventoryAction.DROP_ALL_CURSOR;
            case RIGHT, WINDOW_BORDER_RIGHT -> InventoryAction.DROP_ONE_CURSOR;
            default -> InventoryAction.NOTHING;
        };
    }

    // 预估左键点在某个Inventory槽上的操作: 拿起, 放入, 合并, 交换.
    @NotNull
    private static InventoryAction estimateLeftClick(
            ClickSemantics.LinkedSlot link,
            ItemStack cursor,
            @Nullable ItemStack current
    ) {
        @NotNull InventoryAction result = InventoryAction.SWAP_WITH_CURSOR;
        ClickSlotRules.Outcome outcome = ClickSlotRules.computeLeftClick(
                current,
                cursor,
                link.inventory().slotMaxStackSize(link.slot())
        );
        if (outcome == null) {
            result = InventoryAction.NOTHING;
        } else if (cursor.isEmpty()) {
            result = InventoryAction.PICKUP_ALL;
        } else if (current != null && ItemUtils.isType(cursor, ItemsProxy.BUNDLE)) {
            result = outcome.slotAfter() == null ? InventoryAction.PICKUP_ALL_INTO_BUNDLE : InventoryAction.PICKUP_SOME_INTO_BUNDLE;
        } else if (ItemUtils.isType(current, ItemsProxy.BUNDLE)) {
            result = outcome.cursorAfter().isEmpty() ? InventoryAction.PLACE_ALL_INTO_BUNDLE : InventoryAction.PLACE_SOME_INTO_BUNDLE;
        } else if (current == null) {
            result = InventoryAction.PLACE_ALL;
        } else if (ItemUtils.isSimilar(current, cursor)) {
            int placed = cursor.getAmount() - outcome.cursorAfter().getAmount();
            if (placed == 1) {
                result = InventoryAction.PLACE_ONE;
            } else {
                result = outcome.cursorAfter().isEmpty() ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_SOME;
            }
        }
        return result;
    }

    // 预估右键点在某个Inventory槽上的操作: 拿起一半, 放入一个, 交换.
    @NotNull
    private static InventoryAction estimateRightClick(
            ClickSemantics.LinkedSlot link,
            ItemStack cursor,
            @Nullable ItemStack current
    ) {
        if (ItemUtils.isType(current, ItemsProxy.BUNDLE)) {
            if (cursor.isEmpty()) {
                Object contents = DataComponentHolderProxy.INSTANCE.component(
                        ItemUtils.getItemStackHandle(current),
                        DataComponentsProxy.BUNDLE_CONTENTS
                );
                return contents != null && !BundleContentsProxy.INSTANCE.isEmpty(contents)
                        ? InventoryAction.PICKUP_FROM_BUNDLE
                        : InventoryAction.NOTHING;
            }
            return current.equals(cursor)
                    || ClickSlotRules.computeSwap(current, cursor, link.inventory().slotMaxStackSize(link.slot())) == null
                    ? InventoryAction.NOTHING
                    : InventoryAction.SWAP_WITH_CURSOR;
        }
        ClickSlotRules.Outcome outcome = ClickSlotRules.computeRightClick(current, cursor, link.inventory().slotMaxStackSize(link.slot()));
        if (outcome == null) {
            return InventoryAction.NOTHING;
        }
        if (current == null && ItemUtils.isType(cursor, ItemsProxy.BUNDLE)) {
            return InventoryAction.PLACE_FROM_BUNDLE;
        }
        if (cursor.isEmpty()) {
            return InventoryAction.PICKUP_HALF;
        }
        return ItemUtils.isSimilar(current, cursor) || current == null
                ? InventoryAction.PLACE_ONE
                : InventoryAction.SWAP_WITH_CURSOR;
    }

    // 预估数字键交换.
    @NotNull
    private static InventoryAction estimateHotbarSwap(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot source,
            @Nullable ItemStack sourceItem,
            int hotbarButton
    ) {
        if (hotbarButton < 0 || hotbarButton > 8) {
            return InventoryAction.UNKNOWN;
        }
        ClickSemantics.LinkedSlot target = context.hotbarLink(hotbarButton);
        if (target == null || source.physicalKey().equals(target.physicalKey())) {
            return InventoryAction.NOTHING;
        }
        return sourceItem == null && target.inventory().itemAt(target.slot()) == null
                ? InventoryAction.NOTHING
                : InventoryAction.HOTBAR_SWAP;
    }

    // 预估双击收集.
    @NotNull
    private static InventoryAction estimateCollectToCursor(
            ClickSemantics.Context context,
            ItemStack cursor
    ) {
        if (cursor.isEmpty() || cursor.getAmount() >= cursor.getMaxStackSize()) {
            return InventoryAction.NOTHING;
        }
        List<Inventory> inventories = context.linkedInventories();
        for (int inventoryIndex = 0; inventoryIndex < inventories.size(); inventoryIndex++) {
            ItemStack[] snapshot = inventories.get(inventoryIndex).snapshot();
            for (int slot = 0; slot < snapshot.length; slot++) {
                if (ItemUtils.isSimilar(cursor, snapshot[slot])) {
                    return InventoryAction.COLLECT_TO_CURSOR;
                }
            }
        }
        return InventoryAction.NOTHING;
    }
}
