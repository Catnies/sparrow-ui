package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;

public final class Signals {
    private static final ConcurrentHashMap<Long, Signal<Long>> PERIODIC = new ConcurrentHashMap<>();
    private static volatile TickingSignal ticking;

    private Signals() {
    }

    /**
     * 服务器 tick 源, 值为最近观察到的服务器 tick({@code Bukkit.getCurrentTick()}).
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
        return PERIODIC.computeIfAbsent(periodTicks, period -> ticking().mapDistinct(tick -> tick / period));
    }
}
