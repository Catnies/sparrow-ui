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

    // 左键点击某个槽位的操作, 具体是"放下全部"还是"放下一部分"取决于实际结果, 所以要看规则算出的 outcome.
    @NotNull
    public static InventoryAction leftAction(@Nullable ItemStack current, ItemStack cursor, ClickOutcome outcome) {
        if (cursor.isEmpty()) {
            return InventoryAction.PICKUP_ALL;
        }
        // 收纳袋在两个方向上都有专属操作, 光标是袋子表示把槽里的东西收进去, 槽里是袋子表示把光标塞进去.
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

    // 右键点击某个槽位的操作, 右键的结果形状固定(取一半, 放一个, 交换), 光看两端物品就能定.
    @NotNull
    public static InventoryAction rightAction(@Nullable ItemStack current, ItemStack cursor) {
        // 收纳袋右键是逐件进出, 与左键的整袋收纳区分开; 袋子对袋子仍然只是交换.
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

    // 点到窗口外, 手上没东西就什么也不会发生, 有东西则按左右键决定丢整堆还是丢一个.
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
