package net.momirealms.sparrow.ui.gui;

import org.jetbrains.annotations.NotNull;

/**
 * 表示 GUI 的宽度和高度, 并负责槽位编号与坐标之间的转换.
 *
 * <p>槽位从左到右, 再从上到下编号. 左上角槽位是 0.</p>
 *
 * @param width 非负宽度
 * @param height 非负高度
 */
public record GuiSize(int width, int height) {

    /**
     * 检查宽高为非负数, 且槽位总数没有超出 int 范围.
     *
     * @param width 宽度
     * @param height 高度
     */
    public GuiSize {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("GUI dimensions must be non-negative: " + width + "x" + height);
        }
        Math.multiplyExact(width, height);
    }

    /**
     * 创建一个 GUI 尺寸.
     *
     * @param width 宽度
     * @param height 高度
     * @return GUI 尺寸
     */
    public static @NotNull GuiSize of(int width, int height) {
        return new GuiSize(width, height);
    }

    /**
     * 返回 GUI 的槽位总数.
     *
     * @return 宽度与高度的乘积
     */
    public int area() {
        return this.width * this.height;
    }

    /**
     * 将坐标转换为槽位编号.
     *
     * @param position GUI 内的坐标
     * @return 槽位编号
     */
    public int indexOf(@NotNull GuiPosition position) {
        return this.indexOf(position.x(), position.y());
    }

    /**
     * 将 {@code (x, y)} 坐标转换为槽位编号.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @return 槽位编号
     */
    public int indexOf(int x, int y) {
        if (x < 0 || x >= this.width || y < 0 || y >= this.height) {
            throw new IndexOutOfBoundsException("position (" + x + ", " + y + ") is outside " + this);
        }
        return this.indexOfTrusted(x, y);
    }

    // 跳过边界检查的版本, 供已确认坐标合法的包内调用使用
    int indexOfTrusted(int x, int y) {
        return x + y * this.width;
    }

    /**
     * 将槽位编号转换为坐标.
     *
     * @param slot 槽位编号
     * @return GUI 内的坐标
     */
    public @NotNull GuiPosition positionOf(int slot) {
        this.checkSlot(slot);
        return new GuiPosition(slot % this.width, slot / this.width);
    }

    /**
     * 检查槽位编号是否属于这个 GUI, 并原样返回该编号.
     *
     * @param slot 要检查的槽位编号
     * @return 原槽位编号
     */
    public int checkSlot(int slot) {
        int area = this.area();
        if (slot < 0 || slot >= area) {
            throw new IndexOutOfBoundsException("slot " + slot + " is outside [0, " + area + ") for " + this);
        }
        return slot;
    }
}
