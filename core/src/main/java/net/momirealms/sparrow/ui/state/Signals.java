package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.util.TriFunction;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class Signals {
    private static final long MIN_MILLIS_PERIOD = 50L;              // 毫秒时钟的周期下限, 一个 tick
    private static final WeakPeriodCache<TickingSignal> millisClocks = new WeakPeriodCache<>();  // 周期 -> 毫秒时钟

    private static volatile TickingSignal ticking; // 懒初始化的全局唯一实例
    private static volatile Delayer tickDelayer = Delayer.paperTicks();       // 防抖与节流 tick 基入口用的调度器
    private static volatile Delayer millisDelayer = Delayer.paperMillis();    // 毫秒基入口用的调度器

    private Signals() {
    }

    @NotNull
    static Delayer tickDelayer() {
        return tickDelayer;
    }

    @NotNull
    static Delayer millisDelayer() {
        return millisDelayer;
    }

    /**
     * 服务器 tick 源, 每 tick 失效一次, 值是<strong>本 signal 有订阅者以来经过的 tick 数</strong>.
     *
     * @return tick 源
     */
    @NotNull
    public static Signal<Long> ticking() {
        TickingSignal current = ticking;
        if (current != null) {
            return current;
        }
        synchronized (Signals.class) {
            if (ticking == null) {
                ticking = new TickingSignal(TickingSignal.paperTicker());
            }
            return ticking;
        }
    }

    /**
     * 按周期降频的 tick 源, 每 {@code periodTicks} 个 tick 失效一次, 值为已经过去的周期数.
     * <p>相同周期共享同一个派生节点, 因此所有用同一周期的绑定会在同一 tick 一起失效, 天然合并;
     *
     * @param periodTicks 正数 tick 周期
     * @return 降频后的 tick 源
     */
    @NotNull
    public static Signal<Long> everyTicks(long periodTicks) {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("periodTicks 必须为正数: " + periodTicks);
        }
        if (periodTicks == 1L) {
            return ticking();
        }
        return ((TickingSignal) ticking()).every(periodTicks);
    }

    /**
     * 毫秒时钟, 每 {@code periodMillis} 毫秒失效一次, 值是<strong>有订阅者以来经过的周期数</strong>, 跨停表续走不回退.
     * <p>任务挂在 Paper 异步调度器上, <strong>失效通知在异步线程上发出</strong>, 订阅者回调必须线程安全.
     * 同周期共享一个实例, 没人持有的周期随 GC 消失; 第一个订阅者到来才起任务, 最后一个走了就取消.
     *
     * @param periodMillis 周期毫秒数, 不小于 50
     * @return 毫秒时钟
     * @throws IllegalArgumentException 周期小于 50 毫秒
     */
    @NotNull
    public static Signal<Long> everyMillis(long periodMillis) {
        if (periodMillis < MIN_MILLIS_PERIOD) {
            throw new IllegalArgumentException("periodMillis must be at least " + MIN_MILLIS_PERIOD + ": " + periodMillis);
        }
        return millisClocks.get(periodMillis, period -> new TickingSignal(TickingSignal.paperMillisTicker(period)));
    }

    /**
     * 组合来源, 任一来源失效即失效, 值在拉取时以两个来源的快照重算.
     *
     * @param combiner 纯函数, 可在任意线程被执行
     * @return 组合 signal
     */
    @NotNull
    public static <A, B, R> Signal<R> combine(@NotNull Signal<A> a, @NotNull Signal<B> b, @NotNull BiFunction<? super A, ? super B, ? extends R> combiner) {
        Objects.requireNonNull(combiner, "combiner");
        return new CombinedSignal<>(new AbstractSignal<?>[]{AbstractSignal.require(a), AbstractSignal.require(b)}, values -> {
            @SuppressWarnings("unchecked") R result = combiner.apply((A) values[0], (B) values[1]);
            return result;
        });
    }

    @NotNull
    public static <A, B, C, R> Signal<R> combine(@NotNull Signal<A> a, @NotNull Signal<B> b, @NotNull Signal<C> c, @NotNull TriFunction<? super A, ? super B, ? super C, ? extends R> combiner) {
        Objects.requireNonNull(combiner, "combiner");
        return new CombinedSignal<>(new AbstractSignal<?>[]{AbstractSignal.require(a), AbstractSignal.require(b), AbstractSignal.require(c)}, values -> {
            @SuppressWarnings("unchecked") R result = combiner.apply((A) values[0], (B) values[1], (C) values[2]);
            return result;
        });
    }

    /**
     * 按 key 在分区之间切换, 值取自 {@code key} 当前选中的那个分区.
     * <p>{@code key} 换了或选中的分区失效都向下游失效, {@code key} 重算后仍是同一个 key 时什么都不发生.
     * <p>只有被选中的那个 key 会被取用, 没选中的分区不参与. 异步分区源的首载发生在第一次读, 而不是切过去的那一刻.
     * <p>选中的分区句柄由返回的 signal 强持有, 换 key 时释放.
     *
     * <pre>{@code
     * MutableSignal<Integer> page = Signal.of(0);
     * KeyedSignal<Integer, List<Row>> pages = KeyedSignal.async(List.of(), executor, dao::query);
     * Signal<List<Row>> current = Signals.switching(pages, page);
     * }</pre>
     *
     * @param source 分区数据源
     * @param key 选择分区的 key, 值不得为 {@code null}
     * @return 切换后的 signal
     */
    @NotNull
    public static <K, T> Signal<T> switching(@NotNull KeyedSignal<K, T> source, @NotNull Signal<K> key) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");
        return new SwitchingSignal<>(source::at, AbstractSignal.require(key));
    }

    /**
     * 按 key 在若干现成的 signal 之间切换, 值取自 {@code key} 当前选中的那一个.
     * <p>与分区版的区别在来源上, 本版本从各自独立的 signal 来源, 各有各的失效.
     * 没被选中的来源不参与失效传播, 也不会被求值.
     *
     * <pre>{@code
     * MutableSignal<Category> tab = Signal.of(Category.WEAPONS);
     * Signal<List<Entry>> shown = Signals.switching(Map.of(
     *         Category.WEAPONS, weapons,
     *         Category.ARMOR, armor), tab);
     * }</pre>
     *
     * @param sources 每个 key 对应一个来源, 不能为空
     * @param key 选择来源的 key, 取值必须是 {@code sources} 里有的那些
     * @return 切换后的 signal
     */
    @NotNull
    public static <K, T> Signal<T> switching(@NotNull Map<K, ? extends Signal<T>> sources, @NotNull Signal<K> key) {
        Objects.requireNonNull(key, "key");
        Map<K, ? extends Signal<T>> copied = Map.copyOf(sources);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        return new SwitchingSignal<>(
                selected -> {
                    Signal<T> source = copied.get(selected);
                    if (source == null) {
                        throw new IllegalArgumentException("no source for key: " + selected);
                    }
                    return source;
                },
                AbstractSignal.require(key)
        );
    }

    /**
     * 组合集合的成员, 集合换了成员, 或任何一个成员失效, 返回的 signal 都失效.
     * <p>成员数量可以随时变化, 这是它与 {@link #combine} 的区别.
     * <p>集合每次变化都必须给出一个与旧值不判等的新集合, 原地改集合再写回去, 上游会认为值没变.
     * 集合的迭代顺序还要稳定, 否则同一批成员会被当成换过了.
     *
     * <pre>{@code
     * MutableSignal<List<SparrowInventory>> chests = Signal.of(List.of(left, right));
     * Signal<Long> anyChestChanged = Signals.merging(chests, SparrowInventory::contentSignal);
     * }</pre>
     *
     * @param sources 给出当前成员的集合, 值不得为 {@code null}
     * @param signalOf 把成员换算成它的失效来源
     * @return 汇合后的 signal
     */
    @NotNull
    public static <T> Signal<Long> merging(
            @NotNull Signal<? extends Collection<? extends T>> sources,
            @NotNull Function<? super T, ? extends Signal<?>> signalOf
    ) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(signalOf, "signalOf");
        return new MergingSignal<>(AbstractSignal.require(sources), signalOf);
    }
}
