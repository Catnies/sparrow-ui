package net.momirealms.sparrow.ui.gui;

/**
 * GUI 中一个槽位的坐标. 左上角是 {@code (0, 0)}.
 *
 * @param x 从左向右的位置, 从 0 开始
 * @param y 从上向下的位置, 从 0 开始
 */
public record GuiPosition(int x, int y) {
}
