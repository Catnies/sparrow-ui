package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一个 Pane 的视觉配置: 两层视觉映射, 加上空槽背景.
 * <p>槽位是 Pane 槽位. 映射盖在经过这一槽的显示路径上, 命中时路径终点不再参与显示;
 * 映射的输入是路径终点的同步可读内容: 终点连接 Inventory 时为该槽当前内容(空槽为 {@code null}),
 * 终点是 Item 或空槽位元素时为 {@code null}.
 * <p>Pane 被多个 Window 显示时, 这份配置对所有查看者生效.
 */
public interface PaneVisual extends SlotVisual {

    /**
     * 返回当前空槽背景.
     *
     * @return 空槽背景; 没有设置过时为 {@code null}
     */
    @Nullable
    ItemProvider background();

    /**
     * 替换空槽背景并标脏全部 Pane 槽位.
     * <p>背景只在显示路径终点为空槽位元素时显示; 连接 Inventory 的槽位空了不用它,
     * 那是 {@link InventoryVisual#background()} 的位置.
     *
     * @param background 空槽背景, {@code null} 表示清除
     */
    void background(@Nullable ItemProvider background);

    /**
     * 使用 ItemStack 替换空槽背景并标脏全部 Pane 槽位.
     *
     * @param background 空槽背景
     */
    default void backgroundItem(@NotNull ItemStack background) {
        this.background(ItemProvider.constant(background));
    }
}
