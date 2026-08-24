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
     * <p>第一个订阅者到来时启动, 最后一个离开时停表. 回调由 Paper 全局区域调度线程发出.
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
     * <p>相同周期共享同一个派生节点, 所有使用该周期的绑定会在同一 tick 失效. 回调由 Paper 全局区域调度线程发出.
     *
     * @param periodTicks 正数 tick 周期
     * @return 降频后的 tick 源
     * @throws IllegalArgumentException {@code periodTicks} 小于等于 0
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
     * 同周期共享一个实例, 没人持有的周期随 GC 消失. 第一个订阅者到来时启动, 最后一个离开时取消.
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
     * 组合两个来源, 任一来源失效都会向下游发送失效, 拉取时分别读取两边的当前值.
     * <p>两个读取不构成跨来源的原子快照. {@code combiner} 可能在任意拉取线程执行, 并发拉取时允许重跑.
     *
     * @param <A> 第一个来源的值类型
     * @param <B> 第二个来源的值类型
     * @param <R> 组合结果类型
     * @param a 第一个来源
     * @param b 第二个来源
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

    /**
     * 组合三个来源, 任一来源失效都会向下游发送失效, 拉取时依次读取三个当前值.
     *
     * @param <A> 第一个来源的值类型
     * @param <B> 第二个来源的值类型
     * @param <C> 第三个来源的值类型
     * @param <R> 组合结果类型
     * @param a 第一个来源
     * @param b 第二个来源
     * @param c 第三个来源
     * @param combiner 纯函数, 可在任意线程被执行
     * @return 组合 signal
     */
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
     * <p>只有当前分区参与求值和失效传播. 异步分区的首载由返回 signal 的第一次读取触发.
     * <p>选中的分区句柄由返回的 signal 强持有, 换 key 时释放.
     *
     * <pre>{@code
     * MutableSignal<Integer> page = Signal.of(0);
     * KeyedSignal<Integer, List<Row>> pages = KeyedSignal.async(List.of(), executor, dao::query);
     * Signal<List<Row>> current = Signals.switching(pages, page);
     * }</pre>
     *
     * @param <K> 分区 key 类型
     * @param <T> 值类型
     * @param source 分区数据源
     * @param key 选择分区的 key, 当前值不得为 {@code null}
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
     * <p>每个来源都是独立 signal. 返回值只订阅并读取当前选中的来源.
     *
     * <pre>{@code
     * MutableSignal<Category> tab = Signal.of(Category.WEAPONS);
     * Signal<List<Entry>> shown = Signals.switching(Map.of(
     *         Category.WEAPONS, weapons,
     *         Category.ARMOR, armor), tab);
     * }</pre>
     *
     * @param <K> 选择 key 类型
     * @param <T> 值类型
     * @param sources 每个 key 对应一个来源, 不能为空
     * @param key 选择来源的 key, 取值必须是 {@code sources} 里有的那些
     * @return 切换后的 signal
     * @throws IllegalArgumentException {@code sources} 为空, 或拉取时找不到当前 key 对应的来源
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
     * 汇合集合中的动态成员. 集合成员变化或任一成员 signal 失效时, 返回的 signal 都会失效.
     * <p>普通 {@link MutableSignal} 承载集合时, 更新成员需要发布一个能被判定为新值的集合.
     * {@link ListSignal} 和 {@link SetSignal} 可以直接作为来源并就地修改包装器. 集合迭代顺序必须稳定.
     * <p>返回值是单调递增的失效标记, 数字本身没有业务含义.
     *
     * <pre>{@code
     * ListSignal<SparrowInventory> inventories = ListSignal.of();
     * inventories.add(left);
     * Signal<Long> changed = Signals.merging(inventories, SparrowInventory::contentSignal);
     * }</pre>
     *
     * @param <T> 集合成员类型
     * @param sources 给出当前成员的集合, 值不得为 {@code null}
     * @param signalOf 把成员换算成它的失效来源
     * @return 随成员变化单调递增的 signal
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
