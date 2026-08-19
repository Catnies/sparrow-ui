package net.momirealms.sparrow.ui.util;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
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
    public static final ItemStack EMPTY = ItemStack.empty();

    private ItemUtils() {
    }

    // 返回物品的副本, null 或空物品返回新的空物品; 调用方拥有返回值, 因此不能交出共享的 EMPTY.
    @NotNull
    public static ItemStack copyOrEmpty(@Nullable ItemStack itemStack) {
        return isNullOrEmpty(itemStack) ? ItemStack.empty() : itemStack.clone();
    }

    // 返回物品的副本, null 输入原样返回 null.
    @Nullable
    public static ItemStack copyOrNull(@Nullable ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }

    // 返回数量改为 amount 的物品副本, 原物品不受影响.
    @NotNull
    public static ItemStack copyWithAmount(@NotNull ItemStack source, int amount) {
        ItemStack copy = source.clone();
        copy.setAmount(amount);
        return copy;
    }

    // 把 Bukkit ItemStack 解包为底层 NMS ItemStack, 空物品返回 ItemStackProxy.EMPTY 常量.
    @NotNull
    public static Object getItemStackHandle(@NotNull ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return ItemStackProxy.EMPTY;
        }
        return CraftItemStackProxy.INSTANCE.unwrap(itemStack);
    }

    // 把 null 和空物品统一归一为 null, 其余物品原样返回(不拷贝).
    @Nullable
    public static ItemStack nullIfEmpty(@Nullable ItemStack itemStack) {
        return isNullOrEmpty(itemStack) ? null : itemStack;
    }

    // 把 null 归一为共享空物品, 其余物品原样返回; 返回值一律只读, 调用方不拥有它.
    @NotNull
    public static ItemStack emptyIfNull(@Nullable ItemStack itemStack) {
        return itemStack == null ? EMPTY : itemStack;
    }

    // 返回物品数量, null 或空物品视为 0.
    public static int amountOf(@Nullable ItemStack itemStack) {
        return isNullOrEmpty(itemStack) ? 0 : itemStack.getAmount();
    }

    // 判断物品是否为 null 或空物品.
    public static boolean isNullOrEmpty(@Nullable ItemStack itemStack) {
        return itemStack == null || itemStack.isEmpty();
    }

    // 判断两个物品是否相似(类型与数据一致), 任一为 null 时返回 false.
    public static boolean isSimilar(@Nullable ItemStack a, @Nullable ItemStack b) {
        return a != null && b != null && a.isSimilar(b);
    }

    // 判断两个物品是否表示同一份内容, 判定基于 ItemStack.equals, null 与空物品视为相同.
    public static boolean isContentEqual(@Nullable ItemStack a, @Nullable ItemStack b) {
        return isNullOrEmpty(a) ? isNullOrEmpty(b) : a.equals(b);
    }

    // 判断物品的底层物品类型是否就是给定的 item 对象, null 或空物品返回 false.
    public static boolean isType(@Nullable ItemStack itemStack, @NotNull Object item) {
        return !isNullOrEmpty(itemStack) && ItemStackProxy.INSTANCE.getItem(ItemUtils.getItemStackHandle(itemStack)) == item;
    }

    // 新建一份以屏障为材质且完全不可见的 NMS ItemStack, 可用于隐藏槽位占位.
    public static Object invisibleBarrier() {
        Object item = ItemStackProxy.INSTANCE.newInstance(ItemsProxy.BARRIER);
        ItemUtils.hideTooltips(item);
        return item;
    }

    // 修改物品的可视化组件, 使其在客户端渲染为不可见.
    public static void hideTooltips(Object item) {
        // 隐藏 tooltip 并把模型换成空气纹理, 两者共同保证客户端不可见
        ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.TOOLTIP_DISPLAY, TooltipDisplayProxy.INSTANCE.newInstance(true, new LinkedHashSet<>()));
        ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.ITEM_MODEL, IdentifierProxy.INSTANCE.withDefaultNamespace("air"));
    }

    // 获取玩家指定装备槽位物品的底层 NMS ItemStack.
    public static Object getPlayerItemStackHandle(Player player, EquipmentSlot equipmentSlot) {
        return getItemStackHandle(player.getInventory().getItem(equipmentSlot));
    }
}
