package net.momirealms.sparrow.ui.pane.page;

import net.momirealms.sparrow.ui.state.AsyncSignal;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/**
 * 把一条长序列切成整页, 给出当前页的内容, 当前页码和总页数.
 * <p>翻页按钮由使用方点击里调 {@link #advance(int)} 或 {@link #setPage(int)}, 而显示挂在 {@link #page()} 上.
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
    private static final IntConsumer NONE = ignoredIndex -> {};

    private final MutableSignal<Integer> requested = Signal.of(0); // 使用方想跳到第几页, 还没回夹
    private final Signal<Integer> pageCount;      // 总页数
    private final Signal<Integer> pageIndex;      // 当前页码, 是 requested 回夹之后的值
    private final Signal<List<T>> content;        // 当前这一页的内容
    private final Signal<Integer> contentSize;    // 当前这一页排了几条
    private final IntConsumer refreshAt;   // 拿当前页码重新装载它和总页数; 只有 async 工厂给的是真实现
    private final IntConsumer prefetchAt;  // 拿目标页码提前装载; 只有 async 工厂给的是真实现

    /**
     * 内容一次性全丢进来, 每页条数固定.
     * <p><strong>会复制一份</strong>, 之后改传进来的那个 List 不影响翻页.
     *
     * @param <T> 序列元素类型
     * @param content 完整内容
     * @param pageSize 一页显示多少条, 必须为正数
     * @return 分页
     * @throws IllegalArgumentException pageSize 不是正数时
     */
    @NotNull
    public static <T> Page<T> of(@NotNull List<? extends T> content, int pageSize) {
        return of(Signal.of(List.copyOf(content)), pageSize);
    }

    /**
     * 同 {@link #of(List, int)}, 但每页几条由页码现算, 能做出逐页变化的形状.
     *
     * @param <T> 序列元素类型
     * @param content 完整内容, <strong>会复制一份</strong>
     * @param pageSizeOf 第 n 页显示多少条, <strong>必须返回正数</strong>
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> of(@NotNull List<? extends T> content, @NotNull IntUnaryOperator pageSizeOf) {
        return of(Signal.of(List.copyOf(content)), pageSizeOf);
    }

    /**
     * 翻到哪一页才装载哪一页, 装载和总页数计算都跑在 {@code executor} 上.
     * <p>还没装载完的页显示空内容, 装载完自己刷新; 装过的页留在缓存里, 再翻回去不用重新查.
     *
     * @param <T> 序列元素类型
     * @param executor 执行装载的执行器
     * @param pageOf 装载第 n 页的内容, 在 executor 线程上跑, <strong>必须线程安全</strong>
     * @param pageCountOf 算出总页数, 在 executor 线程上跑, 小于 1 时按 1 处理
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
                pages::get
        );
    }

    /**
     * 收 {@code ListSignal} 的那一个重载.
     * <p>{@code ListSignal} 既是 {@code List} 又是 {@code Signal}, 直接走上面那个重载会被当成内容定死的 List;
     * 想跟着集合变更走就从这里进.
     *
     * @param <T> 序列元素类型
     * @param source 完整序列, 变更时翻页内容跟着变
     * @param pageSize 一页显示多少条, 必须为正数
     * @return 分页
     * @throws IllegalArgumentException pageSize 不是正数时
     */
    @NotNull
    public static <T> Page<T> of(@NotNull ListSignal<? extends T> source, int pageSize) {
        return of((Signal<? extends List<? extends T>>) source, pageSize);
    }

    /**
     * 接 Signal 的翻页, 每页条数固定, 总页数按序列当前长度算.
     *
     * @param <T> 序列元素类型
     * @param source 完整序列
     * @param pageSize 一页显示多少条, 必须为正数
     * @return 分页
     * @throws IllegalArgumentException pageSize 不是正数时
     */
    @NotNull
    public static <T> Page<T> of(@NotNull Signal<? extends List<? extends T>> source, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive: " + pageSize);
        }
        Signal<Integer> pageCount = source.map(list -> Math.max(1, (list.size() + pageSize - 1) / pageSize));
        return new Page<>(pageCount, index -> Signals.combine(source, index, (list, pageIndex) -> slice(list, pageIndex * pageSize, pageSize)), NONE, NONE);
    }

    /**
     * 数据源本来就按页分好区时用它, 页码一变就切到那一页的分区.
     * <p>只订阅当前这一页, 别的分区失不失效跟这里没关系.
     *
     * @param <T> 序列元素类型
     * @param pages 每页一个分区的数据源
     * @param pageCount 总页数, 小于 1 时按 1 处理
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> of(@NotNull KeyedSignal<Integer, List<T>> pages, @NotNull Signal<Integer> pageCount) {
        return new Page<>(pageCount.map(count -> Math.max(1, count)), index -> Signals.switching(pages, index), NONE, NONE);
    }

    /**
     * 收 {@code ListSignal} 的那一个重载, 内容变了翻页跟着变.
     *
     * @param <T> 序列元素类型
     * @param source 完整序列, 变更时翻页内容跟着变
     * @param pageSizeOf 第 n 页显示多少条, <strong>必须返回正数</strong>
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> of(@NotNull ListSignal<? extends T> source, @NotNull IntUnaryOperator pageSizeOf) {
        return of((Signal<? extends List<? extends T>>) source, pageSizeOf);
    }

    /**
     * 每页条数不同的翻页: 页数和切片都问 {@code pageSizeOf}, 从第 0 页一页页排到内容用完.
     *
     * @param <T> 序列元素类型
     * @param source 完整序列
     * @param pageSizeOf 第 n 页显示多少条, <strong>必须返回正数</strong>
     * @return 分页
     */
    @NotNull
    public static <T> Page<T> of(@NotNull Signal<? extends List<? extends T>> source, @NotNull IntUnaryOperator pageSizeOf) {
        Signal<Integer> pageCount = source.map(list -> countOf(list.size(), pageSizeOf));
        Function<Signal<Integer>, Signal<List<T>>> contentOf = index -> Signals.combine(
                source, index,
                (list, pageIndex) -> {
                    return slice(list, offsetOf(pageIndex, pageSizeOf), sizeAt(pageIndex, pageSizeOf));
                }
        );
        return new Page<>(pageCount, contentOf, NONE, NONE);
    }

    private Page(Signal<Integer> pageCount, Function<Signal<Integer>, Signal<List<T>>> contentOf, IntConsumer refreshAt, IntConsumer prefetchAt) {
        this.pageCount = pageCount;
        // 读的时候再夹一道: 内容收窄之后页码自己回落到还有内容的那一页, 不会停在空白页上
        this.pageIndex = Signals.combine(this.requested, pageCount, (req, count) -> Math.clamp(req, 0, count - 1));
        this.content = contentOf.apply(this.pageIndex);
        this.contentSize = this.content.map(List::size);
        this.refreshAt = refreshAt;
        this.prefetchAt = prefetchAt;
    }

    /**
     * 当前这一页的内容, 直接拿去投影.
     *
     * @return 当前页的序列
     */
    @NotNull
    public Signal<List<T>> content() {
        return this.content;
    }

    /**
     * 当前这一页排了几条.
     * <p>它不等于一页最多放多少条: 最后一页装不满时就是实际条数, 没有内容时是 0.
     *
     * @return 当前页的条数
     */
    @NotNull
    public Signal<Integer> contentSize() {
        return this.contentSize;
    }

    /**
     * 从当前页往前翻几页, 负数就是往回翻.
     * <p>越过两端时停在那一端.
     *
     * @param step 翻几页
     */
    public void advance(int step) {
        int targetIndex = this.pageIndex.get() + step;
        targetIndex = Math.clamp(targetIndex, 0, this.pageCount.get() - 1);
        this.requested.set(targetIndex);
    }

    /**
     * 直接跳到某一页, 超出范围时停在最近的一端.
     *
     * @param index 目标页码, 从 0 开始
     */
    public void setPage(int index) {
        this.requested.set(Math.clamp(index, 0, this.pageCount.get() - 1));
    }

    /**
     * 把当前页和总页数重新装载一遍.
     * <p>只有 {@link #async} 建出来的翻页真的会去装载, 别的翻页调它什么也不会发生.
     */
    public void refresh() {
        this.refreshAt.accept(this.pageIndex.get());
    }

    /**
     * 提前把相对当前页第 {@code step} 页装好, 翻过去时就不用等.
     * <p>越界时夹到最近的一端; 已经装过的页不会再查一遍. 同样只有 {@link #async} 建出来的翻页会去装载.
     *
     * @param step 相对当前页的页数, 负数往前
     */
    public void prefetch(int step) {
        int targetIndex = this.pageIndex.get() + step;
        targetIndex = Math.clamp(targetIndex, 0, this.pageCount.get() - 1);
        this.prefetchAt.accept(targetIndex);
    }

    /**
     * 当前页码, 从 0 数.
     * <p>读到的值已经按当前总页数夹过, 所以它永远指向一页真的有内容的地方.
     *
     * @return 当前页码
     */
    @NotNull
    public Signal<Integer> page() {
        return this.pageIndex;
    }

    /**
     * 总页数, 至少是 1.
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
     * @param pageSizeOf 第 n 页显示多少条
     * @return 总页数, 至少为 1
     * @throws IllegalArgumentException {@code pageSizeOf} 给出非正数时
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
     * 第 pageIndex 页的第一条, 在整条序列里排第几个.
     * <p>它是从第 0 页一页页加上来的, 页码越大越慢; 要逐页遍历整条序列时别每页都调一次.
     *
     * @param pageIndex 页码, 从 0 开始
     * @param pageSizeOf 第 n 页显示多少条
     * @return 该页第一条数据的下标
     * @throws IllegalArgumentException {@code pageSizeOf} 给出非正数时
     */
    public static int offsetOf(int pageIndex, @NotNull IntUnaryOperator pageSizeOf) {
        int offset = 0;
        for (int index = 0; index < pageIndex; index++) {
            offset += sizeAt(index, pageSizeOf);
        }
        return offset;
    }

    // 页大小是用户算子算出来的, 非正数在这里就拦下来, 免得后面切片算出负长度
    private static int sizeAt(int pageIndex, IntUnaryOperator pageSizeOf) {
        int size = pageSizeOf.applyAsInt(pageIndex);
        if (size <= 0) {
            throw new IllegalArgumentException("page size must be positive, page " + pageIndex + " gave " + size);
        }
        return size;
    }

    private static <T> List<T> slice(List<? extends T> list, int offset, int length) {
        int size = list.size();
        int from = Math.min(offset, size);
        int to = Math.min(from + length, size);
        // 复制一份再切出去, 页面内容就不会跟着来源 List 之后的原地改动跑
        return new ArrayList<>(list.subList(from, to));
    }
}
