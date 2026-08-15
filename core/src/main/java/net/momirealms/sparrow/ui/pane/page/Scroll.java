package net.momirealms.sparrow.ui.pane.page;

import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 把一条长序列按线铺开, 每次只显示连续的若干线, 滚动换线.
 * <p>竖着滚时一线是一行, 内容从上往下铺; 横着滚时一线是一列, 内容从左往右铺.
 * 两个方向的端口完全一样, {@link #line()} 一族说的"线"按方向各自指行或列.
 * <p>滚动按钮由使用方点击里调 {@link #advance(int)} 或 {@link #line(int)}, 而显示挂在 {@link #line()} 上.
 *
 * <pre>{@code
 * Scroll<Item> scroll = Scroll.vertical(allItems, 9, 3);
 *
 * NormalPane pane = Pane.builder("VVVVVVVVV", "VVVVVVVVV", "VVVVVVVVV", "U#######D")
 *         .addIngredient('V', scroll)
 *         .addIngredient('D', Item.builder()
 *                 .dependsOn(scroll.line())
 *                 .setItemProvider(context -> downArrow(scroll))
 *                 .addClickHandler(click -> scroll.advance(1))
 *                 .build())
 *         .build();
 * }</pre>
 *
 * <p>它与翻页的区别在于相邻两屏是重叠的: 滚一次只换一线, 因此能停的位置是
 * {@code 0} 到 {@link #maxLine()}, 而不是内容线数. 内容不足一屏时哪边都滚不动.
 *
 * <p>线偏移在两处夹取: 滚动和跳线按当时的 {@link #maxLine()} 夹, 于是滚到底再点一下不会把越界的线号攒进状态里;
 * {@link #line()} 每次读取时再夹一次, 于是内容变少之后不会停在一屏空白上. 越界一律夹到最近的一端, 不报错.
 *
 * @param <T> 序列里一条数据的类型
 */
public final class Scroll<T> {
    private final MutableSignal<Integer> requested = Signal.of(0); // 使用方要求的线偏移
    private final Signal<Integer> lineCount;
    private final Signal<Integer> maxLine;
    private final Signal<Integer> line;
    private final Signal<List<T>> content;
    private final Signal<Integer> contentSize;
    private final Orientation orientation;

    /**
     * 全量丢入的竖向滚动声明, 内容当场定死.
     * <p>会复制一份, 之后改动传进来的那个 List 不影响滚动.
     *
     * @param content 完整内容
     * @param width 一行放多少条, 必须为正数
     * @param rows 一次显示多少行, 必须为正数
     * @return 滚动
     */
    @NotNull
    public static <T> Scroll<T> vertical(@NotNull List<? extends T> content, int width, int rows) {
        return vertical(Signal.of(List.copyOf(content)), width, rows);
    }

    /**
     * 竖向滚动声明, 按行宽把序列铺开, 一次显示 {@code rows} 行.
     *
     * @param source 完整序列
     * @param width 一行放多少条, 必须为正数
     * @param rows 一次显示多少行, 必须为正数
     * @return 滚动
     */
    @NotNull
    public static <T> Scroll<T> vertical(@NotNull Signal<? extends List<? extends T>> source, int width, int rows) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
        if (rows <= 0) {
            throw new IllegalArgumentException("rows must be positive: " + rows);
        }
        return new Scroll<>(source, width, rows, Orientation.VERTICAL);
    }

    /**
     * 全量丢入的横向滚动声明, 内容当场定死.
     * <p>会复制一份, 之后改动传进来的那个 List 不影响滚动.
     *
     * @param content 完整内容
     * @param height 一列放多少条, 必须为正数
     * @param columns 一次显示多少列, 必须为正数
     * @return 滚动
     */
    @NotNull
    public static <T> Scroll<T> horizontal(@NotNull List<? extends T> content, int height, int columns) {
        return horizontal(Signal.of(List.copyOf(content)), height, columns);
    }

    /**
     * 横向滚动声明, 按列高把序列铺开, 一次显示 {@code columns} 列.
     * <p>内容的第 n 条落在列主序的第 n 个位置上: 先填满第一列再填第二列.
     * Builder 上的 {@code addIngredient} 会照这个顺序喂槽位, 手动投影时槽位序列要自己转成列主序.
     *
     * @param source 完整序列
     * @param height 一列放多少条, 必须为正数
     * @param columns 一次显示多少列, 必须为正数
     * @return 滚动
     */
    @NotNull
    public static <T> Scroll<T> horizontal(@NotNull Signal<? extends List<? extends T>> source, int height, int columns) {
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive: " + height);
        }
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be positive: " + columns);
        }
        return new Scroll<>(source, height, columns, Orientation.HORIZONTAL);
    }

    private Scroll(Signal<? extends List<? extends T>> source, int lineLength, int visibleLines, Orientation orientation) {
        this.orientation = orientation;
        this.lineCount = source.map(list -> Math.max(1, (list.size() + lineLength - 1) / lineLength));
        this.maxLine = this.lineCount.map(count -> Math.max(0, count - visibleLines));
        this.line = Signal.combine(this.requested, this.maxLine, (req, max) -> Math.clamp(req, 0, max));
        this.content = Signal.combine(source, this.line, (list, line) -> slice(list, line * lineLength, lineLength * visibleLines));
        this.contentSize = this.content.map(List::size);
    }

    /**
     * 返回当前这一屏要显示的内容.
     *
     * @return 当前这一屏的序列
     */
    @NotNull
    public Signal<List<T>> content() {
        return this.content;
    }

    /**
     * 返回当前这一屏实际显示了多少条.
     * <p>它不等于一屏最多放多少条, 滚到底那一屏装不满时返回实际排上去的条数, 没有内容时是 0.
     *
     * @return 当前这一屏的条数
     */
    @NotNull
    public Signal<Integer> contentSize() {
        return this.contentSize;
    }

    /**
     * 相对当前位置滚若干线, 负数往回.
     * <p>想一次滚一屏就传可见线数.
     *
     * @param step 滚几线
     */
    public void advance(int step) {
        int targetLine = this.line.get() + step;
        targetLine = Math.clamp(targetLine, 0, this.maxLine.get());
        this.requested.set(targetLine);
    }

    /**
     * 滚到指定线, 超出范围时停在最近的一端.
     *
     * @param index 目标线偏移, 从 0 开始
     */
    public void line(int index) {
        this.requested.set(Math.clamp(index, 0, this.maxLine.get()));
    }

    /**
     * 返回当前这一屏从第几线开始, 从 0 开始.
     * <p>读到的线偏移已经按当前 {@link #maxLine()} 夹过, 因此它始终指向一屏真的有内容的位置.
     *
     * @return 当前线偏移
     */
    @NotNull
    public Signal<Integer> line() {
        return this.line;
    }

    /**
     * 返回内容一共占了多少线, 至少为 1.
     * <p>最后一线放不满也算一线.
     *
     * @return 内容的线数
     */
    @NotNull
    public Signal<Integer> lineCount() {
        return this.lineCount;
    }

    /**
     * 返回能滚到的最大线偏移, 内容不足一屏时是 0.
     * <p>按钮该不该变灰问它: {@code line} 等于 0 就回不去了, 等于 {@code maxLine} 就滚不动了.
     *
     * @return 最大线偏移
     */
    @NotNull
    public Signal<Integer> maxLine() {
        return this.maxLine;
    }

    /**
     * 返回滚动方向.
     * <p>Builder 用它决定槽位顺序: 横着滚的内容要落在列主序的槽位上.
     *
     * @return 滚动方向
     */
    @NotNull
    public Orientation orientation() {
        return this.orientation;
    }

    /**
     * 从整条序列里取出一屏要显示的那一段.
     *
     * @param list 完整序列
     * @param offset 这一屏从第几条开始
     * @param length 这一屏最多放多少条
     * @return 该屏的内容; 起点已经越过序列末尾时给出空的一段
     */
    private static <T> List<T> slice(List<? extends T> list, int offset, int length) {
        int size = list.size();
        int from = Math.min(offset, size);
        int to = Math.min(from + length, size);
        @SuppressWarnings("unchecked")
        List<T> window = (List<T>) list.subList(from, to);
        return window;
    }

    /**
     * 滚动方向
     */
    public enum Orientation {
        VERTICAL,   // 竖向
        HORIZONTAL  // 横向
    }
}
