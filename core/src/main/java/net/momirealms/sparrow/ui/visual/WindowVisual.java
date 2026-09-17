package net.momirealms.sparrow.ui.visual;

/**
 * 槽号用 Window 坐标. 这一层盖在整条显示路径的最外面, 先于沿途 Pane 和路径终点求值, 命中就到此为止.
 * <p>没有空槽背景. 输入和 {@link PaneVisual} 一样是路径终点的同步可读内容, <strong>只读, 不得修改</strong>.
 */
public interface WindowVisual extends SlotVisual {
}
