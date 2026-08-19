package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentHolderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsMutableProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 收纳袋(Bundle)的点击规则: 四条路径全部经 NMS BundleContents 代理按原版组件规则计算.
 * 纯槽位数学在 {@link ClickSlotRules}, 这里只放需要碰组件的部分.
 */
final class ClickBundleRules {

    private ClickBundleRules() {
    }

    // 使用原版 BundleContents 规则把槽位物品尽量转入光标 Bundle.
    @Nullable
    static ClickSlotRules.Outcome computeInsertionIntoCursorBundle(
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
        return new ClickSlotRules.Outcome(slotAfter.isEmpty() ? null : slotAfter, bundleAfter);
    }

    // 从光标 Bundle 取出选中整组; 槽位放不下的余量重新插回 Bundle.
    @Nullable
    static ClickSlotRules.Outcome computeExtractionFromCursorBundle(
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
        int placed = Math.min(ClickSlotRules.effectiveLimit(slotLimit, taken), taken.getAmount());
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
        return new ClickSlotRules.Outcome(ItemUtils.copyWithAmount(taken, placed), bundleAfter, taken);
    }

    // 使用原版 BundleContents 规则把光标物品尽量插入槽位 Bundle.
    @Nullable
    static ClickSlotRules.Outcome computeBundleInsertion(
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
        return new ClickSlotRules.Outcome(bundleAfter, cursorAfter.isEmpty() ? ItemUtils.EMPTY : cursorAfter);
    }

    // 空手右键槽位 Bundle: 取出选中(或第一件)整组物品上光标.
    @Nullable
    static ClickSlotRules.Outcome computeBundleTake(
            ItemStack current,
            @Nullable ItemStack observedBundle,
            int selectedIndex
    ) {
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
        return new ClickSlotRules.Outcome(bundleAfter, taken);
    }
}
