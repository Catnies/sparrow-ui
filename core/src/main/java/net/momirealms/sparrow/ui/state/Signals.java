package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class Signals {
    private static final long MIN_MILLIS_PERIOD = 50L;              // 毫秒时钟的周期下限, 一个 tick
    private static final long DEFAULT_COUNTDOWN_SAMPLE_TICKS = 20L;  // 倒计时默认每秒采样一次
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
     * 倒计时, 值是距 {@code deadlineMillis} 还剩的毫秒数, 不小于 0, 每秒采样一次.
     * <p>截止是<strong>墙钟毫秒</strong>(epoch), 与冷却、活动结束这类存库的时刻同一个域; 时钟只决定采样节奏.
     * 有订阅期间每个采样周期通知一次, 值按当时的墙钟算; 剩余归零的那一拍再通知最后一次(值 0), 然后把采样时钟摘掉.
     * 截止推后了就重新开始采样, 推到过去就立刻 0 并停. 没有订阅时不挂时钟, 读到的值实时算.
     * <p><strong>有订阅期间只在截止失效时读一次截止并记下, 采样只用记下的那份</strong>, 截止来自
     * {@code PlayerKeyedSignal.at(uuid)} 的句柄时, 玩家退出后采样不会再去碰句柄, 也就不会给离线玩家重建分区.
     * <p>截止为 {@code null} 按已到期处理. 采样通知在全局区域调度线程上发出.
     *
     * <pre>{@code
     * Signal<Long> cooldown = Signals.countdown(cooldownUntil.at(viewerId));
     * Signal<Long> seconds = cooldown.mapDistinct(millis -> (millis + 999) / 1000);   // 每 DEFAULT_COUNTDOWN_SAMPLE_TICKS 只放行一次
     * }</pre>
     *
     * @param deadlineMillis 截止时刻, epoch 毫秒
     * @return 剩余毫秒数
     */
    @NotNull
    public static Signal<Long> countdown(@NotNull Signal<Long> deadlineMillis) {
        return countdown(deadlineMillis, DEFAULT_COUNTDOWN_SAMPLE_TICKS);
    }

    /**
     * 同 {@link #countdown(Signal)}, 但每 {@code sampleTicks} 个 tick 采样一次.
     *
     * @param deadlineMillis 截止时刻, epoch 毫秒
     * @param sampleTicks 采样周期, 必须为正
     * @return 剩余毫秒数
     * @throws IllegalArgumentException {@code sampleTicks} 不是正数
     */
    @NotNull
    public static Signal<Long> countdown(@NotNull Signal<Long> deadlineMillis, long sampleTicks) {
        return new CountdownSignal(AbstractSignal.require(deadlineMillis), AbstractSignal.require(everyTicks(sampleTicks)));
    }

    /**
     * 同 {@link #countdown(Signal)}, 但按毫秒采样, 时钟是 {@link #everyMillis}.
     * <p><strong>采样通知在异步线程上发出</strong>, 与 {@link AsyncSignal} 装载完成的线程同级, 订阅者回调必须线程安全.
     *
     * @param deadlineMillis 截止时刻, epoch 毫秒
     * @param sampleMillis 采样周期毫秒数, 不小于 50
     * @return 剩余毫秒数
     * @throws IllegalArgumentException {@code sampleMillis} 小于 50
     */
    @NotNull
    public static Signal<Long> countdownMillis(@NotNull Signal<Long> deadlineMillis, long sampleMillis) {
        return new CountdownSignal(AbstractSignal.require(deadlineMillis), AbstractSignal.require(everyMillis(sampleMillis)));
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
     * <p>成员数量可以随时变化, 这是它与 {@link Signal#combine} 的区别.
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
