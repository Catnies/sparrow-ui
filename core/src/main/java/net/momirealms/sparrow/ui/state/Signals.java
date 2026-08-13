package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class Signals {
    private static volatile TickingSignal ticking;

    private Signals() {
    }

    /**
     * 服务器 tick 源, 每 tick 失效一次, 值是<strong>本 signal 有订阅者以来经过的 tick 数</strong>.
     * <p>无订阅期间调度任务会停摆, 值也随之冻结, 因此计数只反映"刷新过多少次", 不能用来推算世界时间.
     *
     * <pre>{@code
     * Signal<Long> day = Signals.ticking().mapDistinct(tick -> tick / 24000L);
     * Signal<Season> season = day.mapDistinct(Season::ofDay);
     * }</pre>
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
     * 共享只在有人持有时成立, 最后一个持有方消失后节点连同缓存一起回收.
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
     * 按 key 在分区之间切换, 值取自 {@code key} 当前选中的那个分区.
     * <p>{@code key} 换了或选中的分区失效都向下游失效; {@code key} 重算后仍是同一个 key 时什么都不发生.
     * <p>某个 key 第一次被选中时才取它的分区, 因此异步分区源的首载也发生在这一刻.
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
        return new SwitchingSignal<>(source, AbstractSignal.require(key));
    }
}
