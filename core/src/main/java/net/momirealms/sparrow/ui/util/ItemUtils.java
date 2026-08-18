package net.momirealms.sparrow.ui.util;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.TooltipDisplayProxy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;

public final class ItemUtils {

    private ItemUtils() {
    }

    @NotNull
    public static ItemStack copyOrEmpty(@Nullable ItemStack itemStack) {
        return isNullOrEmpty(itemStack) ? ItemStack.empty() : itemStack.clone();
    }

    @Nullable
    public static ItemStack copyOrNull(@Nullable ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }

    @NotNull
    public static ItemStack copyWithAmount(@NotNull ItemStack source, int amount) {
        ItemStack copy = source.clone();
        copy.setAmount(amount);
        return copy;
    }

    @NotNull
    public static Object getItemStackHandle(@NotNull ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return ItemStackProxy.EMPTY;
        }
        return CraftItemStackProxy.INSTANCE.unwrap(itemStack);
    }

    @Nullable
    public static ItemStack nullIfEmpty(@Nullable ItemStack itemStack) {
        return isNullOrEmpty(itemStack) ? null : itemStack;
    }

    @NotNull
    public static ItemStack emptyIfNull(@Nullable ItemStack itemStack) {
        return itemStack == null ? ItemStack.empty() : itemStack;
    }

    public static int amountOf(@Nullable ItemStack itemStack) {
        return isNullOrEmpty(itemStack) ? 0 : itemStack.getAmount();
    }

    public static boolean isNullOrEmpty(@Nullable ItemStack itemStack) {
        return itemStack == null || itemStack.isEmpty();
    }

    public static boolean isSimilar(@Nullable ItemStack a, @Nullable ItemStack b) {
        return a != null && b != null && a.isSimilar(b);
    }

    // 判断两个物品是否表示同一份内容, 空物品与 null 视为相同.
    public static boolean isContentEqual(@Nullable ItemStack a, @Nullable ItemStack b) {
        return isNullOrEmpty(a) ? isNullOrEmpty(b) : a.equals(b);
    }

    public static boolean isType(@Nullable ItemStack itemStack, @NotNull Object item) {
        return !isNullOrEmpty(itemStack) && ItemStackProxy.INSTANCE.getItem(ItemUtils.getItemStackHandle(itemStack)) == item;
    }

    // 新建一份以屏障作为Material的不可见物品.
    public static Object invisibleBarrier() {
        Object item = ItemStackProxy.INSTANCE.newInstance(ItemsProxy.BARRIER); // 独立 NMS ItemStack
        ItemUtils.hideTooltips(item);
        return item;
    }

    // 修改物品的可视化组件, 使其不可见.
    public static void hideTooltips(Object item) {
        ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.TOOLTIP_DISPLAY, TooltipDisplayProxy.INSTANCE.newInstance(true, new LinkedHashSet<>()));
        ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.ITEM_MODEL, IdentifierProxy.INSTANCE.withDefaultNamespace("air"));
    }

    // 获取玩家背包中指定槽位的 NMS ItemStack.
    public static Object getPlayerItemStackHandle(Player player, EquipmentSlot equipmentSlot) {
        return getItemStackHandle(player.getInventory().getItem(equipmentSlot));
    }
}
