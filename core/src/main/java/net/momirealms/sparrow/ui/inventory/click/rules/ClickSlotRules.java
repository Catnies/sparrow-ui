package net.momirealms.sparrow.ui.inventory.click.rules;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 单槽点击的槽位数学, 涵盖拿起, 放置, 劈半, 合并与整堆交换.
@ApiStatus.Internal
public final class ClickSlotRules {

    private ClickSlotRules() {
    }

    // 算出左键点击后槽位与光标各自的新内容.
    @Nullable
    public static ClickOutcome computeLeftClick(
            @Nullable ItemStack current,
            ItemStack cursor,
            int slotLimit
    ) {
        if (cursor.isEmpty()) {
            return current == null ? null : new ClickOutcome(null, current);
        }
        if (current != null && ClickBundleRules.isBundle(cursor)) {
            return ClickBundleRules.computeInsertionIntoCursorBundle(current, cursor);
        }
        if (ClickBundleRules.isBundle(current)) {
            return ClickBundleRules.computeBundleInsertion(current, cursor);
        }
        if (current == null) {
            int placeable = Math.min(effectiveLimit(slotLimit, cursor), cursor.getAmount());
            if (placeable <= 0) {
                return null;
            }
            return new ClickOutcome(ItemUtils.copyWithAmount(cursor, placeable), remainderOf(cursor, placeable));
        }
        if (ItemUtils.isSimilar(current, cursor)) {
            int space = effectiveLimit(slotLimit, current) - current.getAmount();
            int moved = Math.clamp(space, 0, cursor.getAmount());
            if (moved == 0) {
                return null;
            }
            return new ClickOutcome(ItemUtils.copyWithAmount(current, current.getAmount() + moved), remainderOf(cursor, moved));
        }
        return computeSwap(current, cursor, slotLimit);
    }

    // 算出带 Window 本地 Bundle 选择状态的右键结果.
    @Nullable
    public static ClickOutcome computeRightClick(
            @Nullable ItemStack current,
            ItemStack cursor,
            int slotLimit,
            @Nullable ItemStack observedBundle,
            int selectedIndex
    ) {
        if (current == null && ClickBundleRules.isBundle(cursor)) {
            return ClickBundleRules.computeExtractionFromCursorBundle(cursor, slotLimit);
        }
        if (ClickBundleRules.isBundle(current)) {
            if (!cursor.isEmpty()) {
                return ItemUtils.isContentEqual(current, cursor) ? null : computeSwap(current, cursor, slotLimit);
            }
            assert current != null;
            return ClickBundleRules.computeBundleTake(current, observedBundle, selectedIndex);
        }
        if (cursor.isEmpty()) {
            if (current == null) {
                return null;
            }
            int take = (current.getAmount() + 1) / 2;
            int left = current.getAmount() - take;
            return new ClickOutcome(left > 0 ? ItemUtils.copyWithAmount(current, left) : null, ItemUtils.copyWithAmount(current, take));
        }
        if (current == null) {
            if (effectiveLimit(slotLimit, cursor) <= 0) {
                return null;
            }
            return new ClickOutcome(ItemUtils.copyWithAmount(cursor, 1), remainderOf(cursor, 1));
        }
        if (ItemUtils.isSimilar(current, cursor)) {
            if (effectiveLimit(slotLimit, current) - current.getAmount() <= 0) {
                return null;
            }
            return new ClickOutcome(ItemUtils.copyWithAmount(current, current.getAmount() + 1), remainderOf(cursor, 1));
        }
        return computeSwap(current, cursor, slotLimit);
    }

    // 算出两边物品不同时的整堆交换.
    @Nullable
    public static ClickOutcome computeSwap(
            ItemStack current,
            ItemStack cursor,
            int slotLimit
    ) {
        if (cursor.getAmount() > effectiveLimit(slotLimit, cursor)) {
            return null;
        }
        // 两端内容对调, 数量与组件都不变.
        return new ClickOutcome(cursor, current);
    }

    // 槽位上限与物品自身上限取小, 得到这个物品在这一格真正生效的堆叠上限.
    public static int effectiveLimit(int slotLimit, ItemStack item) {
        return Math.min(slotLimit, item.getMaxStackSize());
    }

    @NotNull
    private static ItemStack remainderOf(ItemStack cursor, int taken) {
        int left = cursor.getAmount() - taken;
        return left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemUtils.EMPTY;
    }
}
