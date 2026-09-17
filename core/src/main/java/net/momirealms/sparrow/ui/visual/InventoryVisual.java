package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 槽号用 Inventory 坐标, 映射的输入就是那一格现在的内容, 空槽给 {@code null}.
 * <p>两层都放行时, 空槽再看一眼背景, 非空槽就按真实内容显示.
 * 输入的所有权看 Inventory 的实现, 一律 <strong>只读, 不得修改</strong>.
 */
public interface InventoryVisual extends SlotVisual {

    /**
     * 空槽位显示什么.
     *
     * @return 空槽背景; 没设时为 null
     */
    @Nullable
    ItemProvider background();

    /**
     * 换掉空槽背景, 顺手把所有 Inventory 槽位标脏.
     *
     * @param background 空槽背景, {@code null} 表示清除
     */
    void background(@Nullable ItemProvider background);

    /**
     * 用固定的 ItemStack 当空槽背景.
     *
     * @param background 空槽背景
     */
    default void backgroundItem(@NotNull ItemStack background) {
        this.background(ItemProvider.constant(background));
    }

    /**
     * 算出一格该显示什么: 两层映射先问, 都放行而且这一格是空的就回退到空槽背景.
     *
     * @param slot Inventory 槽位
     * @param actual 该槽当前内容, 空槽为 {@code null}
     * @return 求值结果, 两层都放行且没有可用背景时为 {@code null}
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    @Nullable
    @ApiStatus.Internal
    ResolvedVisual visualizeWithBackground(int slot, @Nullable ItemStack actual);
}
