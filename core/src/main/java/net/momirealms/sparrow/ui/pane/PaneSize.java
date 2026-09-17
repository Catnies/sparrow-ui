package net.momirealms.sparrow.ui.pane;

import org.jetbrains.annotations.NotNull;

/**
 * Pane 的宽高, 槽号按行优先从左到右往后排.
 * <p>宽高不允许是负数, 面积也不能超出 int; 这两条不成立就直接构造失败, 后面所有槽号换算都站在这个前提上.
 *
 * @param width 宽度, 也就是一行几个槽位
 * @param height 高度, 也就是几行
 */
public record PaneSize(int width, int height) {

    public PaneSize {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Pane dimensions must be non-negative: " + width + "x" + height);
        }
        // 面积会溢出的话在这里就抛出去, 之后 area() 就能放心直接乘
        int ignore = Math.multiplyExact(width, height);
    }

    /**
     * 按宽高建一个尺寸, 校验就在这一步做完.
     *
     * @param width 宽度
     * @param height 高度
     * @return Pane 尺寸
     * @throws IllegalArgumentException 宽度或高度是负数时
     * @throws ArithmeticException 宽度乘高度超出 int 范围时
     */
    @NotNull
    public static PaneSize of(int width, int height) {
        return new PaneSize(width, height);
    }

    /**
     * 槽位总数.
     *
     * @return 宽高的乘积, 也就是槽号的上界(不含)
     */
    public int area() {
        return this.width * this.height;
    }

    /**
     * 把坐标换算成槽号.
     *
     * @param position Pane 内的坐标
     * @return 该格的槽号
     * @throws IndexOutOfBoundsException 坐标跑出 Pane 时
     */
    public int indexOf(@NotNull PanePosition position) {
        return this.indexOf(position.x(), position.y());
    }

    /**
     * 把 {@code (x, y)} 换算成槽号, 顺便检查坐标在不在 Pane 里.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @return 该格的槽号
     * @throws IndexOutOfBoundsException 坐标跑出 Pane 时
     */
    public int indexOf(int x, int y) {
        if (x < 0 || x >= this.width || y < 0 || y >= this.height) {
            throw new IndexOutOfBoundsException("position (" + x + ", " + y + ") is outside " + this);
        }
        return this.indexOfTrusted(x, y);
    }

    /**
     * 换算槽号但不检查边界, 坐标是调用方自己确认过的.
     *
     * @param x 横向坐标, <strong>必须已经在宽度范围内</strong>
     * @param y 纵向坐标, <strong>必须已经在高度范围内</strong>
     * @return 该格的槽号
     */
    int indexOfTrusted(int x, int y) {
        return x + y * this.width;
    }

    /**
     * 把槽号换算回坐标.
     *
     * @param slot 槽号
     * @return Pane 内的坐标
     * @throws IndexOutOfBoundsException 槽号超出范围时
     */
    @NotNull
    public PanePosition positionOf(int slot) {
        this.checkSlot(slot);
        return new PanePosition(slot % this.width, slot / this.width);
    }

    /**
     * 槽号不合法就抛, 合法就原样还回来, 方便接在链式写法里用.
     *
     * @param slot 要检查的槽号
     * @return 同一个槽号
     * @throws IndexOutOfBoundsException 槽号超出范围时
     */
    public int checkSlot(int slot) {
        int area = this.area();
        if (slot < 0 || slot >= area) {
            throw new IndexOutOfBoundsException("slot " + slot + " is outside [0, " + area + ") for " + this);
        }
        return slot;
    }
}
