package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一个 Pane 的视觉配置, 两层视觉映射, 加上空槽背景.
 * <p>槽位使用 Pane 坐标, 映射盖在经过这一槽的显示路径上. 映射命中后直接给出显示结果,
 * 映射的输入是路径终点的同步可读内容, 终点连接 Inventory 时是该槽当前内容(空槽为 {@code null}),
 * 终点是 Item 或空槽位元素时为 {@code null}. <strong>输入只读, 不得修改</strong>.
 */
public interface PaneVisual extends SlotVisual {

    /**
     * 返回空槽背景.
     *
     * @return 空槽背景, 未设置时为 {@code null}
     */
    @Nullable
    ItemProvider background();

    /**
     * 替换空槽背景并标脏全部 Pane 槽位.
     *
     * @param background 空槽背景, {@code null} 表示清除
     */
    void background(@Nullable ItemProvider background);

    /**
     * 使用固定 ItemStack 设置空槽背景.
     *
     * @param background 空槽背景
     */
    default void backgroundItem(@NotNull ItemStack background) {
        this.background(ItemProvider.constant(background));
    }
}
