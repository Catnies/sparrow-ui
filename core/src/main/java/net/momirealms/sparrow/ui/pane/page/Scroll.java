package net.momirealms.sparrow.ui.pane.page;

import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 把一条长序列按线铺开, 一次只显示其中连续的几线, 滚动就换线.
 * <p>竖着滚时一线是一行, 内容从上往下铺; 横着滚时一线是一列, 内容从左往右铺.
 * 两个方向的端口完全一样, {@link #line()} 一族说的"线"按方向各自指行或列.
 * <p>滚动按钮由使用方点击里调 {@link #advance(int)} 或 {@link #setLine(int)}, 而显示挂在 {@link #line()} 上.
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
 * @param <T> 序列里一条数据的类型
 */
public final class Scroll<T> {
    private final MutableSignal<Integer> requested = Signal.of(0); // 使用方想滚到第几线, 还没夹取
    private final Signal<Integer> lineCount;     // 内容一共几线
    private final Signal<Integer> maxLine;       // 能滚到的最大线偏移
    private final Signal<Integer> line;          // 实际停在第几线, 就是 requested 夹进 [0, maxLine] 之后的值
    private final Signal<List<T>> content;       // 当前这一屏的窗口
    private final Signal<Integer> contentSize;   // 当前这一屏排了几条
    private final Orientation orientation;

    /**
     * 竖着滚, 内容一次性全丢进来, 之后就定死了.
     * <p><strong>会复制一份</strong>, 之后改传进来的那个 List 不影响滚动.
     *
     * @param <T> 序列元素类型
     * @param content 完整内容
     * @param width 一行放多少条, 必须为正数
     * @param rows 一次显示多少行, 必须为正数
     * @return 滚动
     * @throws IllegalArgumentException width 或 rows 不是正数时
     */
    @NotNull
    public static <T> Scroll<T> vertical(@NotNull List<? extends T> content, int width, int rows) {
        return vertical(Signal.of(List.copyOf(content)), width, rows);
    }

    /**
     * 收集合装饰器的竖滚, 内容变了滚动跟着变.
     *
     * @param <T> 序列元素类型
     * @param source 完整序列, 变更时滚动内容跟着变
     * @param width 一行放多少条, 必须为正数
     * @param rows 一次显示多少行, 必须为正数
     * @return 滚动
     * @throws IllegalArgumentException width 或 rows 不是正数时
     */
    @NotNull
    public static <T> Scroll<T> vertical(@NotNull ListSignal<? extends T> source, int width, int rows) {
        return vertical((Signal<? extends List<? extends T>>) source, width, rows);
    }

    /**
     * 接 Signal 的竖滚: 内容按 {@code width} 一条一行地铺开, 一次显示 {@code rows} 行.
     *
     * @param <T> 序列元素类型
     * @param source 完整序列
     * @param width 一行放多少条, 必须为正数
     * @param rows 一次显示多少行, 必须为正数
     * @return 滚动
     * @throws IllegalArgumentException width 或 rows 不是正数时
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
     * 横着滚, 内容一次性全丢进来, 之后就定死了.
     * <p><strong>会复制一份</strong>, 之后改传进来的那个 List 不影响滚动.
     *
     * @param <T> 序列元素类型
     * @param content 完整内容
     * @param height 一列放多少条, 必须为正数
     * @param columns 一次显示多少列, 必须为正数
     * @return 滚动
     * @throws IllegalArgumentException height 或 columns 不是正数时
     */
    @NotNull
    public static <T> Scroll<T> horizontal(@NotNull List<? extends T> content, int height, int columns) {
        return horizontal(Signal.of(List.copyOf(content)), height, columns);
    }

    /**
     * 收集合装饰器的横滚, 内容变了滚动跟着变.
     *
     * @param <T> 序列元素类型
     * @param source 完整序列, 变更时滚动内容跟着变
     * @param height 一列放多少条, 必须为正数
     * @param columns 一次显示多少列, 必须为正数
     * @return 滚动
     * @throws IllegalArgumentException height 或 columns 不是正数时
     */
    @NotNull
    public static <T> Scroll<T> horizontal(@NotNull ListSignal<? extends T> source, int height, int columns) {
        return horizontal((Signal<? extends List<? extends T>>) source, height, columns);
    }

    /**
     * 接 Signal 的横滚: 内容按 {@code height} 一条一列地铺开, 一次显示 {@code columns} 列.
     * <p>第 n 条内容落在列主序的第 n 个位置上, 先填满第一列再填第二列.
     * Builder 的 {@code addIngredient} 会照这个顺序喂槽位, 手动投影时槽位序列要自己
     * {@code transform(SlotPatterns.COLUMN_MAJOR)}.
     *
     * @param <T> 序列元素类型
     * @param source 完整序列
     * @param height 一列放多少条, 必须为正数
     * @param columns 一次显示多少列, 必须为正数
     * @return 滚动
     * @throws IllegalArgumentException height 或 columns 不是正数时
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

    // line 是算出来的: 使用方写 requested, 夹进 [0, maxLine] 之后才是真正停在哪一线
    private Scroll(Signal<? extends List<? extends T>> source, int lineLength, int visibleLines, Orientation orientation) {
        this.orientation = orientation;
        this.lineCount = source.map(list -> Math.max(1, (list.size() + lineLength - 1) / lineLength));
        this.maxLine = this.lineCount.map(count -> Math.max(0, count - visibleLines));
        this.line = Signals.combine(this.requested, this.maxLine, (req, max) -> Math.clamp(req, 0, max));
        this.content = Signals.combine(source, this.line, (list, line) -> slice(list, line * lineLength, lineLength * visibleLines));
        this.contentSize = this.content.map(List::size);
    }

    /**
     * 当前这一屏的窗口, 直接拿去投影.
     *
     * @return 当前这一屏的序列
     */
    @NotNull
    public Signal<List<T>> content() {
        return this.content;
    }

    /**
     * 当前这一屏排了几条.
     * <p>最后一屏装不满时就是实际条数, 没有内容时是 0.
     *
     * @return 当前这一屏的条数
     */
    @NotNull
    public Signal<Integer> contentSize() {
        return this.contentSize;
    }

    /**
     * 从当前位置往前滚几线, 负数就是往回滚.
     * <p>越过两端时停在那一端.
     *
     * @param step 滚几线
     */
    public void advance(int step) {
        int targetLine = this.line.get() + step;
        targetLine = Math.clamp(targetLine, 0, this.maxLine.get());
        this.requested.set(targetLine);
    }

    /**
     * 直接滚到某一线, 超出范围时停在最近的一端.
     *
     * @param index 目标线偏移, 从 0 开始
     */
    public void setLine(int index) {
        this.requested.set(Math.clamp(index, 0, this.maxLine.get()));
    }

    /**
     * 当前停在第几线, 从 0 数.
     *
     * @return 当前线偏移
     */
    @NotNull
    public Signal<Integer> line() {
        return this.line;
    }

    /**
     * 内容一共占几线, 至少是 1.
     * <p>最后一线放不满也算一线.
     *
     * @return 内容的线数
     */
    @NotNull
    public Signal<Integer> lineCount() {
        return this.lineCount;
    }

    /**
     * 能滚到的最大线偏移, 内容不足一屏时是 0.
     *
     * @return 最大线偏移
     */
    @NotNull
    public Signal<Integer> maxLine() {
        return this.maxLine;
    }

    /**
     * 这个滚动是竖向还是横向.
     * <p>竖向时一线是一行, 横向时一线是一列.
     *
     * @return 滚动方向
     */
    @NotNull
    public Orientation orientation() {
        return this.orientation;
    }

    // 窗口两头都夹一下: 起点超过内容长度就得到空窗口, 终点也不会越界
    private static <T> List<T> slice(List<? extends T> list, int offset, int length) {
        int size = list.size();
        int from = Math.min(offset, size);
        int to = Math.min(from + length, size);
        @SuppressWarnings("unchecked")
        List<T> window = (List<T>) list.subList(from, to);
        return window;
    }

    public enum Orientation {
        VERTICAL,
        HORIZONTAL
    }
}
