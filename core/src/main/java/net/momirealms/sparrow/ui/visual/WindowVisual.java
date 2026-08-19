package net.momirealms.sparrow.ui.visual;

/**
 * 一个 Window 的槽位视觉配置: 两层视觉映射.
 * <p>槽位是 Window 槽位. 映射盖在整条显示路径的最外层, 先于沿途 Pane 与路径终点求值;
 * 映射的输入是路径终点的同步可读内容: 终点连接 Inventory 时为该槽当前内容(空槽为 {@code null}),
 * 终点是 Item 或空槽位元素时为 {@code null}.
 * <p>Window 只属于一名查看者, 这份配置因此也只影响这一名查看者的显示.
 * 光标不属于任何 Window 槽位, 由 {@link CursorVisual} 单独配置.
 */
public interface WindowVisual extends SlotVisual {
}
