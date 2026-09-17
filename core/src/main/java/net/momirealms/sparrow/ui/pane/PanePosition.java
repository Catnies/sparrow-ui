package net.momirealms.sparrow.ui.pane;

/**
 * Pane 里一格的位置, 左上角是 {@code (0, 0)}.
 *
 * <p>Pane 内部的槽号是行优先编号, 坐标是同一个位置的另一种写法, 两者用
 * {@link PaneSize#indexOf(int, int)} 和 {@link PaneSize#positionOf(int)} 互相换算.
 *
 * @param x 从左数第几列, 从 0 开始
 * @param y 从上数第几行, 从 0 开始
 */
public record PanePosition(int x, int y) {
}
