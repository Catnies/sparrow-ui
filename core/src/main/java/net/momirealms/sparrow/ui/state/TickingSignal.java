package net.momirealms.sparrow.ui.state;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.function.LongConsumer;

final class TickingSignal extends AbstractSignal<Long> {
    private final Ticker ticker;
    private Ticker.Handle handle;
    private volatile Versioned<Long> state = new Versioned<>(0L, 0L);

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
        this.handle = this.ticker.start(this::onTick);
    }

    @Override
    protected void onInactive() {
        this.handle.cancel();
        this.handle = null;
    }

    private void onTick(long tick) {
        Versioned<Long> current = this.state;
        if (current.value() == tick) {
            return;
        }
        this.state = new Versioned<>(tick, current.version() + 1);
        this.notifyDirty();
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
     * 调度抽象, 默认实现用 Paper 全局区域调度器.
     */
    interface Ticker {

        // 启动每 tick 回调, 返回可取消的任务句柄.
        @NotNull
        Handle start(@NotNull LongConsumer onTick);

        interface Handle {

            void cancel();
        }
    }
}
