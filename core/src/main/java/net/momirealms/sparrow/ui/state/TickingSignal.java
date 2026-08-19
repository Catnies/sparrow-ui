package net.momirealms.sparrow.ui.state;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;

final class TickingSignal extends AbstractSignal<Long> {
    private final Ticker ticker;
    private Ticker.Handle handle;
    private volatile Versioned<Long> state = new Versioned<>(0L, 0L);
    private final Map<Long, PeriodicRef> periodic = new HashMap<>();                     // 周期 -> 降频视图, 只弱持有
    private final ReferenceQueue<Signal<Long>> releasedViews = new ReferenceQueue<>();   // 视图已被回收的槽

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
     * 取本 tick 源上的降频视图, 每 {@code periodTicks} 个 tick 失效一次.
     * <p>同周期共享一个节点, 每 tick 的重算次数因此只跟周期种类走, 而不跟绑定数量走.
     */
    @NotNull
    Signal<Long> every(long periodTicks) {
        synchronized (this.periodic) {
            for (Reference<?> released; (released = this.releasedViews.poll()) != null; ) {
                // 只清仍指向这条死引用的槽, 不误删已经重建的视图.
                this.periodic.remove(((PeriodicRef) released).period, released);
            }
            PeriodicRef cached = this.periodic.get(periodTicks);
            Signal<Long> view = cached == null ? null : cached.get();
            if (view == null) {
                view = this.mapDistinct(tick -> tick / periodTicks);
                this.periodic.put(periodTicks, new PeriodicRef(periodTicks, view, this.releasedViews));
            }
            return view;
        }
    }

    // 当前缓存着的降频视图数.
    int periodicViewCount() {
        synchronized (this.periodic) {
            return this.periodic.size();
        }
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

    // 调度抽象, 默认实现用 Paper 全局区域调度器.
    interface Ticker {

        // 启动每 tick 回调, 返回可取消的任务句柄.
        @NotNull
        Handle start(@NotNull LongConsumer onTick);

        interface Handle {

            void cancel();
        }
    }

    // 对降频视图的弱引用, 携带所在周期, 视图被回收后可以直接从引用队列定位并清掉缓存槽.
    private static final class PeriodicRef extends WeakReference<Signal<Long>> {
        private final long period;

        private PeriodicRef(long period, Signal<Long> view, ReferenceQueue<? super Signal<Long>> queue) {
            super(view, queue);
            this.period = period;
        }
    }
}
