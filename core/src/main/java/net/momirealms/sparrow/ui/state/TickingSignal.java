package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
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

    // Bukkit 主线程或 Folia 全局区域线程按 tick 推进计数.
    @NotNull
    static TickingSignal.Ticker platformTicker() {
        return onTick -> {
            // 单个调度任务内只有一个写者
            long[] elapsed = new long[1];
            SchedulerTask task = SparrowUI.getInstance().scheduler().platform()
                    .runRepeating(() -> onTick.accept(++elapsed[0]), 1L, 1L);
            return task::cancel;
        };
    }

    // 同一毫秒时钟的计数和通知串行执行, 取消后排队的回调直接结束.
    @NotNull
    static TickingSignal.Ticker millisTicker(long periodMillis) {
        return onTick -> {
            SerialTick tick = new SerialTick(onTick);
            SchedulerTask task = SparrowUI.getInstance().scheduler().asyncRepeating(
                    tick, periodMillis, periodMillis, TimeUnit.MILLISECONDS
            );
            return () -> {
                tick.cancelled = true;
                task.cancel();
            };
        };
    }

    private static final class SerialTick implements Runnable {
        private final LongConsumer onTick;
        private long elapsed;
        private volatile boolean cancelled;

        private SerialTick(LongConsumer onTick) {
            this.onTick = onTick;
        }

        @Override
        public synchronized void run() {
            if (this.cancelled) return;
            this.onTick.accept(++this.elapsed);
        }
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
