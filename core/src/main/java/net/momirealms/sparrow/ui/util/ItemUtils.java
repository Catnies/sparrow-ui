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

    /**
     * 返回可独立修改的 Bukkit 快照, 并规范化缺失或空物品.
     * <p>空表示收敛为 {@code ItemStack.empty()}, 适用于发包与渲染等需要非 null 实例的场景;
     * 库存域的空表示收敛为 {@code null}, 使用 {@link #nullIfEmpty(ItemStack)}.
     *
     * @param source 源物品堆, 或 {@code null}
     * @return 归调用方所有的物品堆快照
     */
    @NotNull
    public static ItemStack copyOrEmpty(@Nullable ItemStack source) {
        return isNullOrEmpty(source) ? ItemStack.empty() : source.clone();
    }

    /**
     * 返回独立克隆; {@code null} 原样返回.
     */
    @Nullable
    public static ItemStack copyOrNull(@Nullable ItemStack stack) {
        return stack == null ? null : stack.clone();
    }

    /**
     * 返回数量为 {@code amount} 的独立克隆, 源物品不受影响.
     */
    @NotNull
    public static ItemStack copyWithAmount(@NotNull ItemStack source, int amount) {
        ItemStack copy = source.clone();
        copy.setAmount(amount);
        return copy;
    }

    /**
     * 从 Bukkit ItemStack 获取 NMS ItemStack.
     * <p>返回值仍由传入的 Bukkit 物品持有.
     *
     * @param item Bukkit 物品快照
     * @return 借用的底层物品句柄
     */
    @NotNull
    public static Object getItemStackHandle(@NotNull ItemStack item) {
        if (item.isEmpty()) {
            return ItemStackProxy.EMPTY;
        }
        return CraftItemStackProxy.INSTANCE.unwrap(item);
    }

    /**
     * 把空表示统一收敛为 {@code null}, 非空实例原样返回(不克隆).
     * 幂等: 对已归一化的值再次调用结果不变.
     * <p>这是库存域"空槽唯一表示是 null"契约的收敛点; 需要非 null 空实例的
     * 场景使用 {@link #copyOrEmpty(ItemStack)}.
     */
    @Nullable
    public static ItemStack nullIfEmpty(@Nullable ItemStack stack) {
        return isNullOrEmpty(stack) ? null : stack;
    }

    /**
     * 返回数量; 空表示一律计为 0.
     */
    public static int amountOf(@Nullable ItemStack stack) {
        return isNullOrEmpty(stack) ? 0 : stack.getAmount();
    }

    /**
     * 判断是否为空表示: {@code null}, AIR 或数量不大于 0.
     */
    public static boolean isNullOrEmpty(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    /**
     * 相似性判定: 双方均非空且 Bukkit {@code isSimilar} 成立(可堆叠).
     * 任一为 {@code null} 时返回 {@code false}.
     */
    public static boolean isSimilar(@Nullable ItemStack a, @Nullable ItemStack b) {
        return a != null && b != null && a.isSimilar(b);
    }

    /**
     * 新建一份以屏障作为Material的不可见物品.
     *
     * @return NMS ItemStack
     */
    public static Object invisibleBarrier() {
        Object item = ItemStackProxy.INSTANCE.newInstance(ItemsProxy.BARRIER); // 独立 NMS ItemStack
        ItemUtils.hideTooltips(item);
        return item;
    }

    /**
     * 修改物品的可视化组件, 使其不可见.
     *
     * @param item NMS ItemStack
     */
    public static void hideTooltips(Object item) {
        ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.CUSTOM_NAME, ComponentProxy.INSTANCE.empty());
        ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.TOOLTIP_DISPLAY, TooltipDisplayProxy.INSTANCE.newInstance(true, new LinkedHashSet<>()));
        ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.ITEM_MODEL, IdentifierProxy.INSTANCE.withDefaultNamespace("air"));
    }

    /**
     * 获取玩家背包中指定槽位的 NMS ItemStack.
     *
     * @param player 玩家
     * @param equipmentSlot 槽位
     * @return NMS ItemStack
     */
    public static Object getPlayerItemStackHandle(Player player, EquipmentSlot equipmentSlot) {
        return getItemStackHandle(player.getInventory().getItem(equipmentSlot));
    }
}
