package net.momirealms.sparrow.ui.pane.page;

import net.momirealms.sparrow.ui.state.AsyncSignal;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/**
 * 把一条长序列切成整页, 给出当前页的内容, 当前页码和总页数.
 * <p>翻页按钮由使用方点击里调 {@link #advance(int)} 或 {@link #page(int)}, 而显示挂在 {@link #page()} 上.
 *
 * <pre>{@code
 * Page<Item> pages = Page.of(allItems, 18);
 *
 * NormalPane pane = Pane.builder("VVVVVVVVV", "VVVVVVVVV", "P#######N")
 *         .addIngredient('V', pages)
 *         .addIngredient('N', Item.builder()
 *                 .dependsOn(pages.page())
 *                 .setItemProvider(context -> nextArrow(pages))
 *                 .addClickHandler(click -> pages.advance(1))
 *                 .build())
 *         .build();
 * }</pre>
 *
 * @param <T> 序列里一条数据的类型
 */
public final class Page<T> {
    private static final IntConsumer NONE = ignoredIndex -> {}; // 没有装载动作的翻页用的空回调

    private final MutableSignal<Integer> requested = Signal.of(0); // 使用方要求的页码, 可能越界, 由 pageIndex 夹取
    private final Signal<Integer> pageCount;
    private final Signal<Integer> pageIndex;
    private final Signal<List<T>> content;
    private final Signal<Integer> contentSize;
    private final IntConsumer refreshAt;   // 吃当前页码, 把它和页数重新装载; 只有 async 工厂给出真实现
    private final IntConsumer prefetchAt;  // 吃目标页码, 提前装载; 只有 async 工厂给出真实现

    /**
     * 全量丢入的翻页声明, 内容当场定死, 每页条数相同.
     * <p>会复制一份, 之后改动传进来的那个 List 不影响翻页.
     *
     * @param content 完整内容
     * @param pageSize 一页显示多少条, 必须为正数
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> of(@NotNull List<? extends T> content, int pageSize) {
        return of(Signal.of(List.copyOf(content)), pageSize);
    }

    /**
     * 全量丢入且每页条数不同的翻页声明, 每页条数由页码决定.
     *
     * @param content 完整内容, 会复制一份
     * @param pageSizeOf 给出第 n 页显示多少条, 必须返回正数
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> of(@NotNull List<? extends T> content, @NotNull IntUnaryOperator pageSizeOf) {
        return of(Signal.of(List.copyOf(content)), pageSizeOf);
    }

    /**
     * 异步按需装载的翻页声明, 翻到哪一页才在 {@code executor} 上装载哪一页, 总页数也在那里算.
     * <p>未装载完成的页显示空内容, 装载完成后自己刷新, 装载过的页留在缓存里.
     *
     * @param executor 执行装载的执行器
     * @param pageOf 装载第 n 页的内容, 在 executor 线程执行, 必须线程安全
     * @param pageCountOf 给出总页数, 在 executor 线程执行, 小于 1 时按 1 处理
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> async(@NotNull Executor executor, @NotNull IntFunction<? extends List<T>> pageOf, @NotNull IntSupplier pageCountOf) {
        KeyedSignal<Integer, List<T>> pages = KeyedSignal.async(List.of(), executor, pageOf::apply);
        AsyncSignal<Integer> pageCount = Signal.async(1, executor, pageCountOf::getAsInt);
        return new Page<>(
                pageCount.map(count -> Math.max(1, count)),
                index -> Signals.switching(pages, index),
                index -> {
                    pages.dirty(index);
                    pageCount.dirty();
                },
                pages::at
        );
    }

    /**
     * 基础翻页声明, 每页条数相同, 总页数由序列长度算出来.
     *
     * @param source 完整序列
     * @param pageSize 一页显示多少条, 必须为正数
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> of(@NotNull Signal<? extends List<? extends T>> source, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive: " + pageSize);
        }
        Signal<Integer> pageCount = source.map(list -> Math.max(1, (list.size() + pageSize - 1) / pageSize));
        return new Page<>(pageCount, index -> Signal.combine(source, index, (list, pageIndex) -> slice(list, pageIndex * pageSize, pageSize)), NONE, NONE);
    }

    /**
     * 异步装载翻页声明, 每页各是一个分区, 翻到哪一页才加载读取哪一页.
     * <p>分区源用 {@link KeyedSignal#async} 建出来时, 未装载完成的页显示占位值, 装载完成后自己刷新.
     * 想提前把下一页装上就读一次 {@code pages.at(index + 1)}; 想让某一页重新装载就 {@code pages.dirty(index)}.
     *
     * @param pages 每页一个分区的数据源
     * @param pageCount 总页数, 小于 1 时按 1 处理
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> of(@NotNull KeyedSignal<Integer, List<T>> pages, @NotNull Signal<Integer> pageCount) {
        return new Page<>(pageCount.map(count -> Math.max(1, count)), index -> Signals.switching(pages, index), NONE, NONE);
    }

    /**
     * 每页条数不同的翻页声明, 每页条数由页码决定, 用来表达逐页变化的形状.
     * <p>页数与切片都从 {@code pageSizeOf} 推出来, 因此两者不会算岔: 从第 0 页开始按它给的条数一页页排下去,
     * 直到排完全部内容. 例如三行, 两行, 一行循环往复的菜单写成
     * {@code index -> switch (index % 3) { case 0 -> 27; case 1 -> 18; default -> 9; }}.
     *
     * @param source 完整序列
     * @param pageSizeOf 给出第 n 页显示多少条, 必须返回正数
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> of(@NotNull Signal<? extends List<? extends T>> source, @NotNull IntUnaryOperator pageSizeOf) {
        // 从第 0 页开始按每页条数排下去, 排完内容用了几页就是几页.
        Signal<Integer> pageCount = source.map(list -> countOf(list.size(), pageSizeOf));
        Function<Signal<Integer>, Signal<List<T>>> contentOf = index -> Signal.combine(
                source, index,
                (list, pageIndex) -> {
                    return slice(list, offsetOf(pageIndex, pageSizeOf), sizeAt(pageIndex, pageSizeOf));
                }
        );
        return new Page<>(pageCount, contentOf, NONE, NONE);
    }

    private Page(Signal<Integer> pageCount, Function<Signal<Integer>, Signal<List<T>>> contentOf, IntConsumer refreshAt, IntConsumer prefetchAt) {
        this.pageCount = pageCount;
        this.pageIndex = Signal.combine(this.requested, pageCount, (req, count) -> Math.clamp(req, 0, count - 1));
        this.content = contentOf.apply(this.pageIndex);
        this.contentSize = this.content.map(List::size);
        this.refreshAt = refreshAt;
        this.prefetchAt = prefetchAt;
    }

    /**
     * 返回当前页要显示的内容.
     *
     * @return 当前页的序列
     */
    @NotNull
    public Signal<List<T>> content() {
        return this.content;
    }

    /**
     * 返回当前页实际显示了多少条.
     * <p>它不等于这一页最多放多少条, 最后一页装不满时返回实际排上去的条数, 没有内容时是 0.
     *
     * @return 当前页的条数
     */
    @NotNull
    public Signal<Integer> contentSize() {
        return this.contentSize;
    }

    /**
     * 相对当前页翻若干页, 负数往前.
     *
     * @param step 翻几页
     */
    public void advance(int step) {
        int targetIndex = this.pageIndex.get() + step;
        targetIndex = Math.clamp(targetIndex, 0, this.pageCount.get() - 1);
        this.requested.set(targetIndex);
    }

    /**
     * 跳到指定页, 超出范围时停在最近的一端.
     *
     * @param index 目标页码, 从 0 开始
     */
    public void page(int index) {
        this.requested.set(Math.clamp(index, 0, this.pageCount.get() - 1));
    }

    /**
     * 把当前页和总页数重新装载一遍.
     * <p>只有 {@link #async} 建出的翻页有装载动作.
     */
    public void refresh() {
        this.refreshAt.accept(this.pageIndex.get());
    }

    /**
     * 把相对当前页第 {@code step} 页提前装载好, 翻过去时不用等, 越界时夹到最近的一端.
     * <p>只有 {@link #async} 建出的翻页有装载动作, 已经装载过的页不会重查.
     *
     * @param step 相对当前页的页数, 负数往前
     */
    public void prefetch(int step) {
        int targetIndex = this.pageIndex.get() + step;
        targetIndex = Math.clamp(targetIndex, 0, this.pageCount.get() - 1);
        this.prefetchAt.accept(targetIndex);
    }

    /**
     * 返回当前页码, 从 0 开始.
     * <p>读到的页码已经按当前总页数夹过, 因此它始终指向一页真的存在的内容.
     *
     * @return 当前页码
     */
    @NotNull
    public Signal<Integer> page() {
        return this.pageIndex;
    }

    /**
     * 返回总页数, 至少为 1.
     *
     * @return 总页数
     */
    @NotNull
    public Signal<Integer> count() {
        return this.pageCount;
    }

    /**
     * 按每页条数算出一共有几页, 至少 1 页.
     *
     * @param total 一共有多少条数据
     * @param pageSizeOf 给出第 n 页显示多少条
     * @return 总页数, 至少为 1
     * @throws IllegalArgumentException 当 {@code pageSizeOf} 给出非正数时
     */
    public static int countOf(int total, @NotNull IntUnaryOperator pageSizeOf) {
        int covered = 0;
        int index = 0;
        while (covered < total) {
            covered += sizeAt(index, pageSizeOf);
            index++;
        }
        return Math.max(1, index);
    }

    /**
     * 查询某一页的第一条数据在整条序列里排第几个.
     * <p>它从第 0 页一页页加过来, 因此代价随页码增长, 逐页遍历整条序列时不要每页都调一次.
     *
     * @param pageIndex 页码, 从 0 开始
     * @param pageSizeOf 给出第 n 页显示多少条
     * @return 该页第一条数据的下标
     * @throws IllegalArgumentException 当 {@code pageSizeOf} 给出非正数时
     */
    public static int offsetOf(int pageIndex, @NotNull IntUnaryOperator pageSizeOf) {
        // 每页条数可以逐页不同, 起点只能把前面各页的条数一页页加过来.
        // 例如 3, 2, 1 循环往复时, 第 3 页的起点是 3 + 2 + 1 = 6
        int offset = 0;
        for (int index = 0; index < pageIndex; index++) {
            offset += sizeAt(index, pageSizeOf);
        }
        return offset;
    }

    /**
     * 读出某一页显示多少条.
     *
     * @param pageIndex 页码, 从 0 开始
     * @param pageSizeOf 给出第 n 页显示多少条
     * @return 该页显示多少条
     * @throws IllegalArgumentException 当 {@code pageSizeOf} 给出非正数时
     */
    private static int sizeAt(int pageIndex, IntUnaryOperator pageSizeOf) {
        int size = pageSizeOf.applyAsInt(pageIndex);
        if (size <= 0) {
            throw new IllegalArgumentException("page size must be positive, page " + pageIndex + " gave " + size);
        }
        return size;
    }

    /**
     * 从整条序列里取出一页要显示的那一段.
     *
     * @param list 完整序列
     * @param offset 这一页从第几条开始
     * @param length 这一页最多放多少条
     * @return 该页的内容; 起点已经越过序列末尾时给出空的一段
     */
    private static <T> List<T> slice(List<? extends T> list, int offset, int length) {
        int size = list.size();
        int from = Math.min(offset, size);
        int to = Math.min(from + length, size);
        @SuppressWarnings("unchecked")
        List<T> page = (List<T>) list.subList(from, to);
        return page;
    }
}
