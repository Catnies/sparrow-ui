package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 槽号用 Pane 坐标, 映射盖在经过这一格的显示路径上, 命中就按映射给的结果显示.
 * <p>输入是路径终点的同步可读内容. 终点连着 Inventory 时就是那一格当前的内容, 空槽给 {@code null};
 * 终点是 Item 或者干脆是空槽位时也给 {@code null}. <strong>输入只读, 不得修改</strong>.
 */
public interface PaneVisual extends SlotVisual {

    /**
     * 空槽位显示什么.
     *
     * @return 空槽背景; 没设时为 null
     */
    @Nullable
    ItemProvider background();

    /**
     * 换掉空槽背景, 顺手把 Pane 的所有槽位标脏: 空槽显示的就是它.
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
}
