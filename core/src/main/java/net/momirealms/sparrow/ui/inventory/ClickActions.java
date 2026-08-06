package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.tags.ItemTagsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 把槽位与光标的点击结果翻译成 Bukkit 的 {@link InventoryAction}.
 * 纯读取, 不接触规划, 事务与 Window 状态.
 */
final class ClickActions {

    private ClickActions() {
    }

    // 点到窗口外: 手上没东西就什么也不会发生, 有东西则按左右键决定丢整堆还是丢一个.
    @NotNull
    static InventoryAction outsideAction(ItemStack cursor, ClickType clickType) {
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

    // 左键点击某个槽位的操作. 具体是"放下全部"还是"放下一部分"取决于实际结果, 因此要看规则算出的 outcome.
    @NotNull
    static InventoryAction leftAction(
            @Nullable ItemStack current,
            ItemStack cursor,
            ClickSlotRules.Outcome outcome
    ) {
        if (cursor.isEmpty()) {
            return InventoryAction.PICKUP_ALL;
        }
        // 收纳袋在两个方向上都有专属操作: 光标是袋子表示把槽里的东西收进去, 槽里是袋子表示把光标塞进去.
        if (current != null && isBundle(cursor)) {
            return outcome.slotAfter() == null
                    ? InventoryAction.PICKUP_ALL_INTO_BUNDLE
                    : InventoryAction.PICKUP_SOME_INTO_BUNDLE;
        }
        if (isBundle(current)) {
            return outcome.cursorAfter().isEmpty()
                    ? InventoryAction.PLACE_ALL_INTO_BUNDLE
                    : InventoryAction.PLACE_SOME_INTO_BUNDLE;
        }
        if (current == null) {
            return outcome.cursorAfter().isEmpty() ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_SOME;
        }
        // 同种物品是合并: 只挤进去一个时原版单独报 PLACE_ONE, 其余按光标是否清空区分全放和部分放.
        if (ItemUtils.isSimilar(current, cursor)) {
            int placed = cursor.getAmount() - outcome.cursorAfter().getAmount();
            if (placed == 1) {
                return InventoryAction.PLACE_ONE;
            }
            return outcome.cursorAfter().isEmpty() ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_SOME;
        }
        return InventoryAction.SWAP_WITH_CURSOR;
    }

    // 右键点击某个槽位的操作. 右键的结果形状固定(取一半, 放一个, 交换), 不需要看规则算出的 outcome.
    @NotNull
    static InventoryAction rightAction(@Nullable ItemStack current, ItemStack cursor) {
        // 收纳袋右键是逐件进出, 与左键的整袋收纳区分开; 袋子对袋子仍然只是交换.
        if (current == null && isBundle(cursor)) {
            return InventoryAction.PLACE_FROM_BUNDLE;
        }
        if (isBundle(current)) {
            return cursor.isEmpty() ? InventoryAction.PICKUP_FROM_BUNDLE : InventoryAction.SWAP_WITH_CURSOR;
        }
        if (cursor.isEmpty()) {
            return InventoryAction.PICKUP_HALF;
        }
        return current == null || ItemUtils.isSimilar(current, cursor)
                ? InventoryAction.PLACE_ONE
                : InventoryAction.SWAP_WITH_CURSOR;
    }

    /**
     * 判断物品是不是收纳袋. 点击语义里所有的收纳袋判定都走这里.
     * <p>家族判定走 NMS 物品标签 {@code #minecraft:bundles}, 彩色收纳袋与数据包扩展同样命中,
     * 袋内数据仍由深层分支按 BUNDLE_CONTENTS 组件读取.
     * 空槽和空光标必然不是收纳袋, 先挡掉再解析 NMS, 普通空点击不触碰代理.
     *
     * @param item 待判定的物品, 空槽传 {@code null}
     * @return 是收纳袋时返回 {@code true}
     */
    static boolean isBundle(@Nullable ItemStack item) {
        return !ItemUtils.isNullOrEmpty(item) && ItemStackProxy.INSTANCE.is(ItemUtils.getItemStackHandle(item), ItemTagsProxy.BUNDLES);
    }
}
