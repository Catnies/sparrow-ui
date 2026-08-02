package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentHolderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsMutableProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        if (current != null && ItemUtils.isType(cursor, ItemsProxy.BUNDLE)) {
            return computeInsertionIntoCursorBundle(current, cursor);
        }
        if (ItemUtils.isType(current, ItemsProxy.BUNDLE)) {
            return computeBundleInsertion(current, cursor);
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
        if (current == null && ItemUtils.isType(cursor, ItemsProxy.BUNDLE)) {
            return computeExtractionFromCursorBundle(cursor, slotLimit);
        }
        if (ItemUtils.isType(current, ItemsProxy.BUNDLE)) {
            if (!cursor.isEmpty()) {
                return current.equals(cursor) ? null : computeSwap(current, cursor, slotLimit);
            }
            ItemStack bundleAfter = current.clone();
            Object bundleHandle = ItemUtils.getItemStackHandle(bundleAfter);
            Object contents = DataComponentHolderProxy.INSTANCE.component(bundleHandle, DataComponentsProxy.BUNDLE_CONTENTS);
            if (contents == null || BundleContentsProxy.INSTANCE.isEmpty(contents)) {
                return null;
            }
            int takeIndex = observedBundle != null
                    && observedBundle.equals(current)
                    && selectedIndex >= 0
                    && selectedIndex < BundleContentsProxy.INSTANCE.size(contents)
                    ? selectedIndex
                    : 0;
            Object mutableContents = BundleContentsMutableProxy.INSTANCE.newInstance(contents);
            int previousSelection = VersionHelper.isOrAbove26_1()
                    ? BundleContentsProxy.INSTANCE.selectedItemIndex(contents)
                    : BundleContentsProxy.INSTANCE.selectedItem(contents);
            if (previousSelection >= 0) {
                BundleContentsMutableProxy.INSTANCE.toggleSelectedItem(mutableContents, previousSelection);
            }
            BundleContentsMutableProxy.INSTANCE.toggleSelectedItem(mutableContents, takeIndex);
            Object takenHandle = BundleContentsMutableProxy.INSTANCE.removeOne(mutableContents);
            ItemStackProxy.INSTANCE.set(
                    bundleHandle,
                    DataComponentsProxy.BUNDLE_CONTENTS,
                    BundleContentsMutableProxy.INSTANCE.toImmutable(mutableContents)
            );
            ItemStack taken = CraftItemStackProxy.INSTANCE.asCraftMirror(takenHandle).clone();
            if (taken.isEmpty()) {
                return null;
            }
            return new Outcome(bundleAfter, taken);
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

    // 使用原版 BundleContents 规则把槽位物品尽量转入光标 Bundle.
    @Nullable
    private static Outcome computeInsertionIntoCursorBundle(
            ItemStack current,
            ItemStack cursor
    ) {
        ItemStack slotAfter = current.clone();
        ItemStack bundleAfter = cursor.clone();
        Object bundleHandle = ItemUtils.getItemStackHandle(bundleAfter);
        Object contents = DataComponentHolderProxy.INSTANCE.component(bundleHandle, DataComponentsProxy.BUNDLE_CONTENTS);
        if (contents == null) {
            return null;
        }
        Object mutableContents = BundleContentsMutableProxy.INSTANCE.newInstance(contents);
        int inserted = BundleContentsMutableProxy.INSTANCE.tryInsert(mutableContents, ItemUtils.getItemStackHandle(slotAfter));
        if (inserted == 0) {
            return null;
        }
        ItemStackProxy.INSTANCE.set(
                bundleHandle,
                DataComponentsProxy.BUNDLE_CONTENTS,
                BundleContentsMutableProxy.INSTANCE.toImmutable(mutableContents)
        );
        return new Outcome(slotAfter.isEmpty() ? null : slotAfter, bundleAfter);
    }

    // 从光标 Bundle 取出选中整组; 槽位放不下的余量重新插回 Bundle.
    @Nullable
    private static Outcome computeExtractionFromCursorBundle(
            ItemStack cursor,
            int slotLimit
    ) {
        ItemStack bundleAfter = cursor.clone();
        Object bundleHandle = ItemUtils.getItemStackHandle(bundleAfter);
        Object contents = DataComponentHolderProxy.INSTANCE.component(bundleHandle, DataComponentsProxy.BUNDLE_CONTENTS);
        if (contents == null || BundleContentsProxy.INSTANCE.isEmpty(contents)) {
            return null;
        }
        Object mutableContents = BundleContentsMutableProxy.INSTANCE.newInstance(contents);
        Object takenHandle = BundleContentsMutableProxy.INSTANCE.removeOne(mutableContents);
        if (takenHandle == null) {
            return null;
        }
        ItemStack taken = CraftItemStackProxy.INSTANCE.asCraftMirror(takenHandle).clone();
        int placed = Math.min(effectiveLimit(slotLimit, taken), taken.getAmount());
        if (placed <= 0) {
            return null;
        }
        int remainder = taken.getAmount() - placed;
        if (remainder > 0) {
            ItemStack remainderStack = ItemUtils.copyWithAmount(taken, remainder);
            int reinserted = BundleContentsMutableProxy.INSTANCE.tryInsert(
                    mutableContents,
                    ItemUtils.getItemStackHandle(remainderStack)
            );
            if (reinserted != remainder) {
                return null;
            }
        }
        ItemStackProxy.INSTANCE.set(
                bundleHandle,
                DataComponentsProxy.BUNDLE_CONTENTS,
                BundleContentsMutableProxy.INSTANCE.toImmutable(mutableContents)
        );
        return new Outcome(ItemUtils.copyWithAmount(taken, placed), bundleAfter);
    }

    // 使用原版 BundleContents 规则把光标物品尽量插入 Bundle.
    @Nullable
    private static Outcome computeBundleInsertion(
            ItemStack current,
            ItemStack cursor
    ) {
        ItemStack bundleAfter = current.clone();
        ItemStack cursorAfter = cursor.clone();
        Object bundleHandle = ItemUtils.getItemStackHandle(bundleAfter);
        Object contents = DataComponentHolderProxy.INSTANCE.component(bundleHandle, DataComponentsProxy.BUNDLE_CONTENTS);
        if (contents == null) {
            return null;
        }
        Object mutableContents = BundleContentsMutableProxy.INSTANCE.newInstance(contents);
        int inserted = BundleContentsMutableProxy.INSTANCE.tryInsert(mutableContents, ItemUtils.getItemStackHandle(cursorAfter));
        if (inserted == 0) {
            return null;
        }
        ItemStackProxy.INSTANCE.set(
                bundleHandle,
                DataComponentsProxy.BUNDLE_CONTENTS,
                BundleContentsMutableProxy.INSTANCE.toImmutable(mutableContents)
        );
        return new Outcome(bundleAfter, cursorAfter.isEmpty() ? ItemStack.empty() : cursorAfter);
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
        return new Outcome(cursor.clone(), current);
    }

    private static int effectiveLimit(int slotLimit, ItemStack item) {
        return Math.min(slotLimit, item.getMaxStackSize());
    }

    @NotNull
    private static ItemStack remainderOf(ItemStack cursor, int taken) {
        int left = cursor.getAmount() - taken;
        return left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemStack.empty();
    }

    // 槽位与光标的点击结果.
    record Outcome(@Nullable ItemStack slotAfter, @NotNull ItemStack cursorAfter) {
    }
}
