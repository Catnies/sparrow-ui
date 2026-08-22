package net.momirealms.sparrow.ui.inventory.click.rules;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentHolderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.tags.ItemTagsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsMutableProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

// 收纳袋的点击规则, 四条路径一律交给 NMS BundleContents 代理按原版组件规则算.
// 纯槽位数学计算在 ClickSlotRules, 这里只放非碰组件不可的部分.
@ApiStatus.Internal
public final class ClickBundleRules {

    private ClickBundleRules() {
    }

    // 点击语义里所有的收纳袋判定都走这里. 认的是 NMS 物品标签 #minecraft:bundles, 彩色收纳袋和数据包扩展同样命中,
    // 袋内数据由下面四条路径按 BUNDLE_CONTENTS 组件读. 空槽与空光标先短路掉, 普通空点击碰不到代理.
    public static boolean isBundle(@Nullable ItemStack item) {
        return !ItemUtils.isNullOrEmpty(item) && ItemStackProxy.INSTANCE.is(ItemUtils.getItemStackHandle(item), ItemTagsProxy.BUNDLES);
    }

    // 使用原版 BundleContents 规则把槽位物品尽量转入光标 Bundle.
    @Nullable
    public static ClickOutcome computeInsertionIntoCursorBundle(
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
        return new ClickOutcome(slotAfter.isEmpty() ? null : slotAfter, bundleAfter);
    }

    // 从光标 Bundle 取出选中整组, 槽位放不下的余量重新插回 Bundle.
    @Nullable
    public static ClickOutcome computeExtractionFromCursorBundle(
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
        // 槽位吃不下的那部分原样塞回袋子. 塞不回去说明这次取出收支不平, 整条路径作废, 一件都不动
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
        return new ClickOutcome(ItemUtils.copyWithAmount(taken, placed), bundleAfter, taken);
    }

    // 使用原版 BundleContents 规则把光标物品尽量插入槽位 Bundle.
    @Nullable
    public static ClickOutcome computeBundleInsertion(
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
        return new ClickOutcome(bundleAfter, cursorAfter.isEmpty() ? ItemUtils.EMPTY : cursorAfter);
    }

    // 空手右键槽位 Bundle, 取出选中(或第一件)整组物品上光标.
    @Nullable
    public static ClickOutcome computeBundleTake(
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
        // 只认客户端当时看的就是这只袋子的那次选择, 对不上或没选就退回第一件
        int takeIndex = observedBundle != null
                && observedBundle.equals(current)
                && selectedIndex >= 0
                && selectedIndex < BundleContentsProxy.INSTANCE.size(contents)
                ? selectedIndex
                : 0;
        Object mutableContents = BundleContentsMutableProxy.INSTANCE.newInstance(contents);
        // 26.1 起改名成 selectedItemIndex, 老版本仍叫 selectedItem
        int previousSelection = VersionHelper.isOrAbove26_1()
                ? BundleContentsProxy.INSTANCE.selectedItemIndex(contents)
                : BundleContentsProxy.INSTANCE.selectedItem(contents);
        // toggle 是开关语义, 对同一个索引连按两次等于没选.
        // 先关掉旧选中再 toggle 目标, 第二次一定落在"选上", removeOne 才取到想要的那一件
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
        return new ClickOutcome(bundleAfter, taken);
    }
}
