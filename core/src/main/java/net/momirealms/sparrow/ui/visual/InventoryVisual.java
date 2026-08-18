package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一个 SparrowInventory 的视觉配置: 两层视觉映射, 加上空槽背景.
 * <p>槽位是 Inventory 槽位. 映射的输入是该槽当前内容, 空槽为 {@code null};
 * 两层映射都放行时, 空槽还会看一眼空槽背景, 非空槽则按真实内容显示.
 * <p><strong>映射收到的是容器内部的物品实例, 不是副本, 一律不得修改</strong>.
 */
public interface InventoryVisual extends SlotVisual {

    /**
     * 返回当前空槽背景.
     *
     * @return 空槽背景; 没有设置过时为 {@code null}
     */
    @Nullable
    ItemProvider background();

    /**
     * 替换空槽背景并标脏全部 Inventory 槽位.
     *
     * @param background 空槽背景, {@code null} 表示清除
     */
    void background(@Nullable ItemProvider background);

    /**
     * 使用 ItemStack 替换空槽背景并标脏全部 Inventory 槽位.
     *
     * @param background 空槽背景
     */
    default void backgroundItem(@NotNull ItemStack background) {
        this.background(ItemProvider.constant(background));
    }
}
