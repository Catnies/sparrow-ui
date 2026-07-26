package net.momirealms.sparrow.ui.inventory;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Inventory 值层的统一归一化工具.
 * <p>子系统内部对"空槽"只有一种表示: {@code null}; 任何 AIR 或数量不大于 0 的实例
 * 都必须先经本类归一化, 后续代码不再手写空值判断.
 */
final class ItemStackValues {

    private ItemStackValues() {
    }

    /**
     * 把空表示统一收敛为 {@code null}, 非空实例原样返回(不克隆).
     * 幂等: 对已归一化的值再次调用结果不变.
     */
    @Nullable
    static ItemStack normalize(@Nullable ItemStack stack) {
        return isEmpty(stack) ? null : stack;
    }

    /**
     * 判断是否为空表示: {@code null}, AIR 或数量不大于 0.
     */
    static boolean isEmpty(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    /**
     * 返回独立克隆; {@code null} 原样返回.
     * 读写路径跨越 Inventory 边界时都经此方法, 保证内部实例永不外泄.
     */
    @Nullable
    static ItemStack cloneOrNull(@Nullable ItemStack stack) {
        return stack == null ? null : stack.clone();
    }

    /**
     * 相似性判定: 双方均非空且 Bukkit {@code isSimilar} 成立(可堆叠).
     * 任一为 {@code null} 时返回 {@code false}.
     */
    static boolean isSimilar(@Nullable ItemStack a, @Nullable ItemStack b) {
        return a != null && b != null && a.isSimilar(b);
    }

    /**
     * 返回数量; 空表示一律计为 0.
     */
    static int amountOf(@Nullable ItemStack stack) {
        return isEmpty(stack) ? 0 : stack.getAmount();
    }
}
