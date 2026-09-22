package net.momirealms.sparrow.ui.util;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.util.UnitProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.TooltipDisplayProxy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;

public final class ItemUtils {
    public static final ItemStack EMPTY = empty();

    private ItemUtils() {
    }

    /**
     * 创建数量为零的空气物品, 返回值由调用方持有并可独立修改.
     *
     * @return 新的空物品
     */
    @NotNull
    public static ItemStack empty() {
        return new ItemStack(Material.AIR, 0);
    }

    public static boolean isEmpty(@NotNull ItemStack itemStack) {
        if (VersionHelper.hasPaperPatch || CraftItemStackProxy.CLASS.isInstance(itemStack)) {
            return ItemStackProxy.INSTANCE.isEmpty(CraftItemStackProxy.INSTANCE.unwrap(itemStack));
        }
        // Spigot 普通 Bukkit 物品没有 NMS 句柄, 判空只读取其数量和材质.
        return itemStack.getAmount() <= 0 || itemStack.getType().isAir();
    }

    /**
     * 复制物品, {@code null} 或空物品返回一份新的空物品.
     *
     * @param itemStack 原物品, 或 {@code null}
     * @return 由调用方持有的副本
     */
    @NotNull
    public static ItemStack copyOrEmpty(@Nullable ItemStack itemStack) {
        return isNullOrEmpty(itemStack) ? empty() : itemStack.clone();
    }

    /**
     * 复制物品, {@code null} 输入仍返回 {@code null}.
     *
     * @param itemStack 原物品, 或 {@code null}
     * @return 物品副本, 或 {@code null}
     */
    @Nullable
    public static ItemStack copyOrNull(@Nullable ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }

    /**
     * 复制物品并设置副本数量.
     *
     * @param source 原物品
     * @param amount 副本数量
     * @return 修改数量后的副本
     */
    @NotNull
    public static ItemStack copyWithAmount(@NotNull ItemStack source, int amount) {
        ItemStack copy = source.clone();
        copy.setAmount(amount);
        return copy;
    }

    /**
     * 返回 Bukkit ItemStack 对应的 NMS 句柄, 空物品返回底层共享空实例.
     * <p>Paper 和 CraftItemStack 复用原有句柄; Spigot 的普通 Bukkit 物品需要转换为新的 NMS 物品.
     * <p><strong>返回值可能与输入共享数据, 调用方不得修改.</strong>
     *
     * @param itemStack Bukkit 物品
     * @return NMS ItemStack
     */
    @NotNull
    public static Object getItemStackHandle(@NotNull ItemStack itemStack) {
        if (!VersionHelper.hasPaperPatch && !CraftItemStackProxy.CLASS.isInstance(itemStack) && isEmpty(itemStack)) {
            return ItemStackProxy.EMPTY;
        }
        Object handle = CraftItemStackProxy.INSTANCE.unwrap(itemStack);
        return ItemStackProxy.INSTANCE.isEmpty(handle) ? ItemStackProxy.EMPTY : handle;
    }

    /**
     * 将 {@code null} 和空物品归一为 {@code null}, 其余物品原样返回.
     *
     * @param itemStack 待归一的物品
     * @return 原物品, 或 {@code null}
     */
    @Nullable
    public static ItemStack nullIfEmpty(@Nullable ItemStack itemStack) {
        return isNullOrEmpty(itemStack) ? null : itemStack;
    }

    /**
     * 将 {@code null} 归一为共享空物品, 其余物品原样返回.
     * <p><strong>返回值为借用引用, 调用方不得修改.</strong>
     *
     * @param itemStack 原物品, 或 {@code null}
     * @return 非空物品引用
     */
    @NotNull
    public static ItemStack emptyIfNull(@Nullable ItemStack itemStack) {
        return itemStack == null ? EMPTY : itemStack;
    }

    /**
     * 返回物品数量, {@code null} 和空物品按 0 计算.
     *
     * @param itemStack 物品, 或 {@code null}
     * @return 物品数量
     */
    public static int amountOf(@Nullable ItemStack itemStack) {
        return isNullOrEmpty(itemStack) ? 0 : itemStack.getAmount();
    }

    /**
     * 判断物品是否为 {@code null} 或空物品.
     *
     * @param itemStack 待检查物品
     * @return 为空时返回 {@code true}
     */
    public static boolean isNullOrEmpty(@Nullable ItemStack itemStack) {
        return itemStack == null || isEmpty(itemStack);
    }

    /**
     * 比较两个物品的类型与组件, 不比较数量.
     *
     * @param a 第一个物品
     * @param b 第二个物品
     * @return 内容相似且两者均非 {@code null} 时返回 {@code true}
     */
    public static boolean isSimilar(@Nullable ItemStack a, @Nullable ItemStack b) {
        return a != null && b != null && ItemStackProxy.INSTANCE.isSameItemSameComponents(getItemStackHandle(a), getItemStackHandle(b));
    }

    /**
     * 比较 Bukkit 物品与已解包的 NMS 句柄, 不比较数量.
     * <p>适合在循环外解包固定的一侧, 避免每次比较都转换非 CraftItemStack.
     *
     * @param item Bukkit 物品
     * @param handle NMS ItemStack
     * @return 内容相似且 item 非 {@code null} 时返回 {@code true}
     */
    public static boolean isSimilarToHandle(@Nullable ItemStack item, @NotNull Object handle) {
        return item != null && ItemStackProxy.INSTANCE.isSameItemSameComponents(getItemStackHandle(item), handle);
    }

    /**
     * 比较两个物品的类型, 组件与数量, {@code null} 和空物品视为相同.
     *
     * @param a 第一个物品
     * @param b 第二个物品
     * @return 内容完全相同时返回 {@code true}
     */
    public static boolean isContentEqual(@Nullable ItemStack a, @Nullable ItemStack b) {
        return isNullOrEmpty(a) ? isNullOrEmpty(b) : b != null && ItemStackProxy.INSTANCE.matches(getItemStackHandle(a), getItemStackHandle(b));
    }

    /**
     * 比较 NMS 句柄与预期 Bukkit 物品的类型, 组件和数量.
     * <p>{@code null} 与空 expected 都表示期望空物品.
     *
     * @param handle NMS ItemStack
     * @param expected 预期 Bukkit 物品
     * @return 内容完全相同时返回 {@code true}
     */
    public static boolean isHandleContentEqual(@NotNull Object handle, @Nullable ItemStack expected) {
        boolean expectedEmpty = isNullOrEmpty(expected);
        if (handle == ItemStackProxy.EMPTY || ItemStackProxy.INSTANCE.isEmpty(handle)) {
            return expectedEmpty;
        }
        return !expectedEmpty && ItemStackProxy.INSTANCE.matches(handle, getItemStackHandle(expected));
    }

    /**
     * 按对象身份比较物品的底层类型.
     *
     * @param itemStack Bukkit 物品
     * @param item NMS Item
     * @return 底层 Item 为同一对象时返回 {@code true}
     */
    public static boolean isType(@Nullable ItemStack itemStack, @NotNull Object item) {
        return !isNullOrEmpty(itemStack) && ItemStackProxy.INSTANCE.getItem(ItemUtils.getItemStackHandle(itemStack)) == item;
    }

    /**
     * 创建客户端不可见的屏障物品, 用作仍需保留物品身份的占位内容.
     *
     * @return 新的 NMS ItemStack
     */
    public static Object invisibleBarrier() {
        Object item = ItemStackProxy.INSTANCE.newInstance(ItemsProxy.BARRIER);
        ItemUtils.hideTooltips(item);
        return item;
    }

    /**
     * 修改 NMS ItemStack 的客户端显示组件, 隐藏名称, 提示与模型.
     *
     * @param item 要修改的 NMS ItemStack
     */
    public static void hideTooltips(Object item) {
        ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.CUSTOM_NAME, ComponentProxy.INSTANCE.empty());
        if (VersionHelper.isOrAbove1_21_5) {
            ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.INSTANCE.TOOLTIP_DISPLAY(), TooltipDisplayProxy.INSTANCE.newInstance(true, new LinkedHashSet<>()));
        } else {
            ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.INSTANCE.HIDE_TOOLTIP(), UnitProxy.INSTANCE.getInstance());
        }
        ItemStackProxy.INSTANCE.set(item, DataComponentsProxy.ITEM_MODEL, IdentifierProxy.INSTANCE.withDefaultNamespace("air"));
    }

    /**
     * 返回玩家装备槽位中物品的 NMS 句柄.
     * <p><strong>返回值为借用引用, 调用方不得修改.</strong>
     *
     * @param player 玩家
     * @param equipmentSlot 装备槽位
     * @return NMS ItemStack
     */
    public static Object getPlayerItemStackHandle(Player player, EquipmentSlot equipmentSlot) {
        return getItemStackHandle(player.getInventory().getItem(equipmentSlot));
    }
}
