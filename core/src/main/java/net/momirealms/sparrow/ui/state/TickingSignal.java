package net.momirealms.sparrow.ui.state;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

final class TickingSignal extends AbstractSignal<Long> {
    private final Ticker ticker;
    private Ticker.Handle handle;
    private long epochBase; // 本段调度启动时的冻结值, 回调的段内计数叠在它之上, 值因此跨停表续走不回退
    private volatile Versioned<Long> state = new Versioned<>(0L, 0L);
    private final WeakPeriodCache<Signal<Long>> periodic = new WeakPeriodCache<>();   // 周期 -> 降频视图

    TickingSignal(Ticker ticker) {
        this.ticker = ticker;
    }

    @Override
    public Long get() {
        return this.state.value();
    }

    @Override
    long version() {
        return this.state.version();
    }

    @Override
    protected void onActive() {
        this.epochBase = this.state.value();
        this.handle = this.ticker.start(this::onTick);
    }

    @Override
    protected void onInactive() {
        this.handle.cancel();
        this.handle = null;
    }

    private void onTick(long tick) {
        long total = this.epochBase + tick;
        Versioned<Long> current = this.state;
        if (current.value() == total) return;
        this.state = new Versioned<>(total, current.version() + 1);
        this.notifyDirty();
    }

    /**
     * 取本 tick 源上的降频视图, 每 {@code periodTicks} 个 tick 失效一次.
     * <p>同周期共享一个节点, 每 tick 的重算次数因此只跟周期种类走, 而不跟绑定数量走.
     */
    @NotNull
    Signal<Long> every(long periodTicks) {
        return this.periodic.get(periodTicks, period -> this.mapDistinct(tick -> tick / period));
    }

    // 当前缓存着的降频视图数.
    int periodicViewCount() {
        return this.periodic.size();
    }

    /**
     * 全局区域调度器实现, 自己数回调次数而<strong>不去问服务器当前 tick</strong>.
     * <p>{@code Bukkit.getCurrentTick()} 在 Folia 上只在区域 tick 内合法.
     */
    @NotNull
    static TickingSignal.Ticker paperTicker() {
        return onTick -> {
            Plugin plugin = SparrowUI.getInstance().getPlugin();
            // 只被调度任务这一个线程改写, 不需要原子性
            long[] elapsed = new long[1];
            ScheduledTask task = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, ignoredTask -> onTick.accept(++elapsed[0]), 1L, 1L);
            return task::cancel;
        };
    }

    /**
     * 毫秒时钟的调度实现, 挂在 Paper 异步调度器上, 回调在异步线程.
     * <p>与 tick 版一样自己数回调次数.
     */
    @NotNull
    static TickingSignal.Ticker paperMillisTicker(long periodMillis) {
        return onTick -> {
            Plugin plugin = SparrowUI.getInstance().getPlugin();
            // 相邻两拍可能落在不同的池线程上, 但调度器要等这一拍跑完才排下一拍, 计数始终只有一个写者
            long[] elapsed = new long[1];
            ScheduledTask task = Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin, ignoredTask -> onTick.accept(++elapsed[0]), periodMillis, periodMillis, TimeUnit.MILLISECONDS
            );
            return task::cancel;
        };
    }

    // 调度抽象, 默认实现用 Paper 全局区域调度器.
    interface Ticker {

        // 启动每 tick 回调, 返回可取消的任务句柄.
        @NotNull
        Handle start(@NotNull LongConsumer onTick);

        interface Handle {

            void cancel();
        }
    }
}
