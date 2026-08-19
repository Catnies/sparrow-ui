package net.momirealms.sparrow.ui.pane;

import org.jetbrains.annotations.NotNull;

public record PaneSize(int width, int height) {

    public PaneSize {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Pane dimensions must be non-negative: " + width + "x" + height);
        }
        int ignore = Math.multiplyExact(width, height);
    }

    /**
     * 创建一个 Pane 尺寸.
     *
     * @param width 宽度
     * @param height 高度
     * @return Pane 尺寸
     */
    @NotNull
    public static PaneSize of(int width, int height) {
        return new PaneSize(width, height);
    }

    /**
     * 返回 Pane 的槽位总数.
     *
     * @return 宽度与高度的乘积
     */
    public int area() {
        return this.width * this.height;
    }

    /**
     * 将坐标转换为槽位编号.
     *
     * @param position Pane 内的坐标
     * @return 槽位编号
     * @throws IndexOutOfBoundsException 坐标超出 Pane 范围时抛出
     */
    public int indexOf(@NotNull PanePosition position) {
        return this.indexOf(position.x(), position.y());
    }

    /**
     * 将 {@code (x, y)} 坐标转换为槽位编号.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @return 槽位编号
     * @throws IndexOutOfBoundsException 坐标超出 Pane 范围时抛出
     */
    public int indexOf(int x, int y) {
        if (x < 0 || x >= this.width || y < 0 || y >= this.height) {
            throw new IndexOutOfBoundsException("position (" + x + ", " + y + ") is outside " + this);
        }
        return this.indexOfTrusted(x, y);
    }

    /**
     * 将已确认合法的 {@code (x, y)} 坐标转换为槽位编号, 跳过边界检查.
     *
     * @param x 横向坐标, 必须已在宽度范围内
     * @param y 纵向坐标, 必须已在高度范围内
     * @return 槽位编号
     */
    int indexOfTrusted(int x, int y) {
        return x + y * this.width;
    }

    /**
     * 将槽位编号转换为坐标.
     *
     * @param slot 槽位编号
     * @return Pane 内的坐标
     * @throws IndexOutOfBoundsException 槽位编号超出范围时抛出
     */
    @NotNull
    public PanePosition positionOf(int slot) {
        this.checkSlot(slot);
        return new PanePosition(slot % this.width, slot / this.width);
    }

    /**
     * 检查槽位编号是否属于这个 Pane, 并原样返回该编号.
     *
     * @param slot 要检查的槽位编号
     * @return 原槽位编号
     * @throws IndexOutOfBoundsException 槽位编号超出范围时抛出
     */
    public int checkSlot(int slot) {
        int area = this.area();
        if (slot < 0 || slot >= area) {
            throw new IndexOutOfBoundsException("slot " + slot + " is outside [0, " + area + ") for " + this);
        }
        return slot;
    }
}
