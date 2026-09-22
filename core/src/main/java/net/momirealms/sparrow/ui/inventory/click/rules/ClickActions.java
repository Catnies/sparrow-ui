package net.momirealms.sparrow.ui.inventory.click.rules;

import net.momirealms.sparrow.ui.inventory.event.InventoryClickAction;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class ClickActions {

    private ClickActions() {
    }

    @NotNull
    public static InventoryClickAction leftAction(@Nullable ItemStack current, ItemStack cursor, ClickOutcome outcome) {
        if (ItemUtils.isEmpty(cursor)) {
            return InventoryClickAction.PICKUP_ALL;
        }
        // Bundle 的操作名取决于袋子位于光标还是槽位.
        if (current != null && ClickBundleRules.isBundle(cursor)) {
            return outcome.slotAfter() == null
                    ? InventoryClickAction.PICKUP_ALL_INTO_BUNDLE
                    : InventoryClickAction.PICKUP_SOME_INTO_BUNDLE;
        }
        if (ClickBundleRules.isBundle(current)) {
            return ItemUtils.isEmpty(outcome.cursorAfter())
                    ? InventoryClickAction.PLACE_ALL_INTO_BUNDLE
                    : InventoryClickAction.PLACE_SOME_INTO_BUNDLE;
        }
        if (current == null) {
            return ItemUtils.isEmpty(outcome.cursorAfter()) ? InventoryClickAction.PLACE_ALL : InventoryClickAction.PLACE_SOME;
        }
        // 同种物品是合并, 只挤进去一个时原版单独报 PLACE_ONE, 其余按光标是否清空区分全放和部分放.
        if (ItemUtils.isSimilar(current, cursor)) {
            int placed = cursor.getAmount() - outcome.cursorAfter().getAmount();
            if (placed == 1) {
                return InventoryClickAction.PLACE_ONE;
            }
            return ItemUtils.isEmpty(outcome.cursorAfter()) ? InventoryClickAction.PLACE_ALL : InventoryClickAction.PLACE_SOME;
        }
        return InventoryClickAction.SWAP_WITH_CURSOR;
    }

    @NotNull
    public static InventoryClickAction rightAction(@Nullable ItemStack current, ItemStack cursor) {
        // Bundle 右键使用逐件进出操作名.
        if (current == null && ClickBundleRules.isBundle(cursor)) {
            return InventoryClickAction.PLACE_FROM_BUNDLE;
        }
        if (ClickBundleRules.isBundle(current)) {
            return ItemUtils.isEmpty(cursor) ? InventoryClickAction.PICKUP_FROM_BUNDLE : InventoryClickAction.SWAP_WITH_CURSOR;
        }
        if (ItemUtils.isEmpty(cursor)) {
            return InventoryClickAction.PICKUP_HALF;
        }
        return current == null || ItemUtils.isSimilar(current, cursor)
                ? InventoryClickAction.PLACE_ONE
                : InventoryClickAction.SWAP_WITH_CURSOR;
    }

    @NotNull
    public static InventoryClickAction outsideAction(ItemStack cursor, ClickType clickType) {
        if (clickType == ClickType.UNKNOWN || clickType == ClickType.CREATIVE) {
            return InventoryClickAction.UNKNOWN;
        }
        if (ItemUtils.isEmpty(cursor)) {
            return InventoryClickAction.NOTHING;
        }
        return switch (clickType) {
            case LEFT, WINDOW_BORDER_LEFT -> InventoryClickAction.DROP_ALL_CURSOR;
            case RIGHT, WINDOW_BORDER_RIGHT -> InventoryClickAction.DROP_ONE_CURSOR;
            default -> InventoryClickAction.NOTHING;
        };
    }
}
