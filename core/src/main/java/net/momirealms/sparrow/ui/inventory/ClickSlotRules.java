package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 单槽点击的槽位数学: 拿起, 放置, 劈半, 合并与整堆交换;
final class ClickSlotRules {

    private ClickSlotRules() {
    }

    // 算出左键点击后槽位与光标各自的新内容.
    @Nullable
    static Outcome computeLeftClick(
            @Nullable ItemStack current,
            ItemStack cursor,
            int slotLimit
    ) {
        if (cursor.isEmpty()) {
            return current == null ? null : new Outcome(null, current);
        }
        // 收纳袋组件就转给 ClickBundleRules.
        if (current != null && ClickActions.isBundle(cursor)) {
            return ClickBundleRules.computeInsertionIntoCursorBundle(current, cursor);
        }
        if (ClickActions.isBundle(current)) {
            return ClickBundleRules.computeBundleInsertion(current, cursor);
        }
        if (current == null) {
            int placeable = Math.min(effectiveLimit(slotLimit, cursor), cursor.getAmount());
            if (placeable <= 0) {
                return null;
            }
            return new Outcome(ItemUtils.copyWithAmount(cursor, placeable), remainderOf(cursor, placeable));
        }
        if (ItemUtils.isSimilar(current, cursor)) {
            int space = effectiveLimit(slotLimit, current) - current.getAmount();
            int moved = Math.clamp(space, 0, cursor.getAmount());
            if (moved == 0) {
                return null;
            }
            return new Outcome(ItemUtils.copyWithAmount(current, current.getAmount() + moved), remainderOf(cursor, moved));
        }
        return computeSwap(current, cursor, slotLimit);
    }

    // 算出带 Window 本地 Bundle 选择状态的右键结果.
    @Nullable
    static Outcome computeRightClick(
            @Nullable ItemStack current,
            ItemStack cursor,
            int slotLimit,
            @Nullable ItemStack observedBundle,
            int selectedIndex
    ) {
        // 收纳袋组件就转给 ClickBundleRules.
        if (current == null && ClickActions.isBundle(cursor)) {
            return ClickBundleRules.computeExtractionFromCursorBundle(cursor, slotLimit);
        }
        if (ClickActions.isBundle(current)) {
            if (!cursor.isEmpty()) {
                return current.equals(cursor) ? null : computeSwap(current, cursor, slotLimit);
            }
            return ClickBundleRules.computeBundleTake(current, observedBundle, selectedIndex);
        }
        if (cursor.isEmpty()) {
            if (current == null) {
                return null;
            }
            int take = (current.getAmount() + 1) / 2;
            int left = current.getAmount() - take;
            return new Outcome(left > 0 ? ItemUtils.copyWithAmount(current, left) : null, ItemUtils.copyWithAmount(current, take));
        }
        if (current == null) {
            if (effectiveLimit(slotLimit, cursor) <= 0) {
                return null;
            }
            return new Outcome(ItemUtils.copyWithAmount(cursor, 1), remainderOf(cursor, 1));
        }
        if (ItemUtils.isSimilar(current, cursor)) {
            if (effectiveLimit(slotLimit, current) - current.getAmount() <= 0) {
                return null;
            }
            return new Outcome(ItemUtils.copyWithAmount(current, current.getAmount() + 1), remainderOf(cursor, 1));
        }
        return computeSwap(current, cursor, slotLimit);
    }

    // 算出两边物品不同时的整堆交换.
    @Nullable
    static Outcome computeSwap(
            ItemStack current,
            ItemStack cursor,
            int slotLimit
    ) {
        if (cursor.getAmount() > effectiveLimit(slotLimit, cursor)) {
            return null;
        }
        // 整堆交换: 两端内容对调, 数量与组件都不变.
        return new Outcome(cursor, current);
    }

    // 计算槽位对这个物品真正生效的堆叠上限: 槽位上限与物品自身上限取小.
    static int effectiveLimit(int slotLimit, ItemStack item) {
        return Math.min(slotLimit, item.getMaxStackSize());
    }

    @NotNull
    private static ItemStack remainderOf(ItemStack cursor, int taken) {
        int left = cursor.getAmount() - taken;
        return left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemUtils.EMPTY;
    }

    // 槽位与光标的点击结果. placementInput 是这次真正被放进槽位的物品, 只有从收纳袋里掏东西时才与
    // 光标本身不同: 光标拿着袋子, 落进槽位的却是袋子里的某一件, 槽级放入规则要检查的是后者.
    record Outcome(@Nullable ItemStack slotAfter, @NotNull ItemStack cursorAfter, @Nullable ItemStack placementInput) {

        // 放入物就是光标本身的常规结果, 由调用方自己从光标取值.
        Outcome(@Nullable ItemStack slotAfter, @NotNull ItemStack cursorAfter) {
            this(slotAfter, cursorAfter, null);
        }
    }
}
