package net.momirealms.sparrow.ui.inventory.click.rules;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class ClickActions {

    private ClickActions() {
    }

    @NotNull
    public static InventoryAction leftAction(@Nullable ItemStack current, ItemStack cursor, ClickOutcome outcome) {
        if (cursor.isEmpty()) {
            return InventoryAction.PICKUP_ALL;
        }
        // Bundle 的操作名取决于袋子位于光标还是槽位.
        if (current != null && ClickBundleRules.isBundle(cursor)) {
            return outcome.slotAfter() == null
                    ? InventoryAction.PICKUP_ALL_INTO_BUNDLE
                    : InventoryAction.PICKUP_SOME_INTO_BUNDLE;
        }
        if (ClickBundleRules.isBundle(current)) {
            return outcome.cursorAfter().isEmpty()
                    ? InventoryAction.PLACE_ALL_INTO_BUNDLE
                    : InventoryAction.PLACE_SOME_INTO_BUNDLE;
        }
        if (current == null) {
            return outcome.cursorAfter().isEmpty() ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_SOME;
        }
        // 同种物品是合并, 只挤进去一个时原版单独报 PLACE_ONE, 其余按光标是否清空区分全放和部分放.
        if (ItemUtils.isSimilar(current, cursor)) {
            int placed = cursor.getAmount() - outcome.cursorAfter().getAmount();
            if (placed == 1) {
                return InventoryAction.PLACE_ONE;
            }
            return outcome.cursorAfter().isEmpty() ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_SOME;
        }
        return InventoryAction.SWAP_WITH_CURSOR;
    }

    @NotNull
    public static InventoryAction rightAction(@Nullable ItemStack current, ItemStack cursor) {
        // Bundle 右键使用逐件进出操作名.
        if (current == null && ClickBundleRules.isBundle(cursor)) {
            return InventoryAction.PLACE_FROM_BUNDLE;
        }
        if (ClickBundleRules.isBundle(current)) {
            return cursor.isEmpty() ? InventoryAction.PICKUP_FROM_BUNDLE : InventoryAction.SWAP_WITH_CURSOR;
        }
        if (cursor.isEmpty()) {
            return InventoryAction.PICKUP_HALF;
        }
        return current == null || ItemUtils.isSimilar(current, cursor)
                ? InventoryAction.PLACE_ONE
                : InventoryAction.SWAP_WITH_CURSOR;
    }

    @NotNull
    public static InventoryAction outsideAction(ItemStack cursor, ClickType clickType) {
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
}
