package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一个 SparrowInventory 的视觉配置, 两层视觉映射, 加上空槽背景.
 * <p>槽位是 Inventory 槽位, 映射的输入是该槽当前内容, 空槽为 {@code null};
 * 两层映射都放行时, 空槽再看一眼空槽背景, 非空槽按真实内容显示.
 * <p><strong>映射收到的是容器内部的物品实例, 不是副本, 一律不得修改</strong>.
 */
public interface InventoryVisual extends SlotVisual {

    @Nullable
    ItemProvider background();

    /**
     * 替换空槽背景并标脏全部 Inventory 槽位.
     *
     * @param background 空槽背景, {@code null} 表示清除.
     */
    void background(@Nullable ItemProvider background);

    default void backgroundItem(@NotNull ItemStack background) {
        this.background(ItemProvider.constant(background));
    }

    /**
     * 求值一个槽位的两层视觉映射, 都放行且该槽为空时回退到空槽背景.
     *
     * @param slot Inventory 槽位
     * @param actual 该槽当前内容, 空槽为 {@code null}
     * @return 求值结果, 两层都放行且没有可用背景时为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    @ApiStatus.Internal
    ResolvedVisual visualizeWithBackground(int slot, @Nullable ItemStack actual);
}
