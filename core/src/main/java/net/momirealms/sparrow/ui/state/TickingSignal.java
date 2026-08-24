package net.momirealms.sparrow.ui.state;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

final class TickingSignal extends AbstractSignal<Long> {
    private final Ticker ticker;
    private Ticker.Handle handle;
    private final AtomicReference<Versioned<Long>> state = new AtomicReference<>(new Versioned<>(0L, 0L));
    private final WeakPeriodCache<Signal<Long>> periodic = new WeakPeriodCache<>();   // 周期 -> 弱缓存的降频视图

    TickingSignal(Ticker ticker) {
        this.ticker = ticker;
    }

    @Override
    public Long get() {
        return this.state.get().value();
    }

    @Override
    long version() {
        return this.state.get().version();
    }

    @Override
    protected void onActive() {
        // 回调携带本激活段的起点, 段内计数叠加其上. 迟到任务仍使用所属段起点, 总值跨停表单调递增.
        long base = this.state.get().value();
        this.handle = this.ticker.start(tick -> this.onTick(base, tick));
    }

    @Override
    protected void onInactive() {
        this.handle.cancel();
        this.handle = null;
    }

    private void onTick(long base, long tick) {
        long total = base + tick;
        while (true) {
            Versioned<Long> current = this.state.get();
            // 旧段迟到值不得覆盖新段进度, 不同调度线程经 CAS 发布单调值
            if (total <= current.value()) return;
            if (this.state.compareAndSet(current, new Versioned<>(total, current.version() + 1))) break;
        }
        this.notifyDirty();
    }

    // 同周期共享降频视图, 每 tick 的重算次数只随周期种类增长
    @NotNull
    Signal<Long> every(long periodTicks) {
        return this.periodic.get(periodTicks, period -> this.mapDistinct(tick -> tick / period));
    }

    int periodicViewCount() {
        return this.periodic.size();
    }

    // 全局区域调度器无法在 Folia 上调用 Bukkit.getCurrentTick(), 因此直接累计回调次数
    @NotNull
    static TickingSignal.Ticker paperTicker() {
        return onTick -> {
            Plugin plugin = SparrowUI.getInstance().getPlugin();
            // 单个调度任务内只有一个写者
            long[] elapsed = new long[1];
            ScheduledTask task = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, ignoredTask -> onTick.accept(++elapsed[0]), 1L, 1L);
            return task::cancel;
        };
    }

    // 毫秒时钟挂在 Paper 异步调度器上, 相邻回调可能使用不同池线程
    @NotNull
    static TickingSignal.Ticker paperMillisTicker(long periodMillis) {
        return onTick -> {
            Plugin plugin = SparrowUI.getInstance().getPlugin();
            // 调度器串行安排同一任务的各拍, 计数始终只有一个写者
            long[] elapsed = new long[1];
            ScheduledTask task = Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin, ignoredTask -> onTick.accept(++elapsed[0]), periodMillis, periodMillis, TimeUnit.MILLISECONDS
            );
            return task::cancel;
        };
    }

    // 测试可替换的周期调度入口
    interface Ticker {

        @NotNull
        Handle start(@NotNull LongConsumer onTick);

        interface Handle {

            void cancel();
        }
    }
}
