package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

final class AsyncSignalImpl<T> extends AbstractSignal<T> implements AsyncSignal<T> {
    // 加载状态机
    private static final int UNLOADED = 0;      // 尚未调度过任何加载
    private static final int IDLE = 1;          // 没有进行中的加载
    private static final int LOADING = 2;       // 一轮加载进行中
    private static final int LOADING_DIRTY = 3; // 加载中又登记了失效, 本轮完成后补跑一轮
    // 调度参数
    private static final int MAX_SCHEDULE_ATTEMPTS = 2;     // 首次提交, 加上为并发失效补的一次重试
    private static final long MILLIS_PER_TICK = 50L;
    // 加载函数与已发布状态
    private final Executor executor;
    private final Supplier<? extends T> loader;
    private final BiPredicate<? super T, ? super T> sameValue;
    private final AtomicReference<Versioned<T>> state;
    private final AtomicInteger loadState = new AtomicInteger(UNLOADED);
    // 当前加载与轮询生命周期
    @Nullable private Thread loadingThread;             // 正在执行 loader 的线程, 用于拒绝自失效
    @Nullable private final PollingState polling;        // null 表示普通异步来源

    AsyncSignalImpl(T placeholder, Executor executor, Supplier<? extends T> loader, BiPredicate<? super T, ? super T> sameValue, @Nullable Polling polling) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.sameValue = Objects.requireNonNull(sameValue, "sameValue");
        this.state = new AtomicReference<>(new Versioned<>(placeholder, 0L));
        this.polling = polling == null ? null : new PollingState(polling);
    }

    // 调度首载, 分区来源会在第一次真实取用时调用
    void scheduleInitialLoad() {
        if (this.loadState.compareAndSet(UNLOADED, LOADING)) {
            // 首载被拒后恢复 UNLOADED. 后续访问只会从这个状态重新提交首载.
            this.scheduleLoad(UNLOADED);
        }
    }

    @Override
    public T get() {
        return this.state.get().value();
    }

    @Override
    long version() {
        return this.state.get().version();
    }

    @Override
    protected void onActive() {
        PollingState polling = this.polling;
        if (polling == null) return;
        long generation = ++polling.generation;
        polling.clockSubscription = this.linkTo(polling.settings.clock(), () -> this.onPollTick(polling, generation));
        try {
            // 空闲值超过一个周期时立即补载, 首载未提交或仍在执行时不叠加
            if (this.loadState.get() == IDLE && System.nanoTime() - polling.lastCompletedNanos >= polling.settings.periodNanos()) {
                this.dirty();
            }
        } catch (RuntimeException | Error exception) {
            // 激活补载失败时撤销时钟订阅, 配合 register 回滚
            polling.clockSubscription.close();
            polling.clockSubscription = null;
            throw exception;
        }
    }

    @Override
    protected void onInactive() {
        PollingState polling = this.polling;
        if (polling == null) return;
        assert polling.clockSubscription != null;
        polling.clockSubscription.close();
        polling.clockSubscription = null;
    }

    // 轮询到拍时先清理整条派生链. 值长期不变也能发现空链并经 onInactive 停表.
    private void onPollTick(PollingState polling, long generation) {
        // 时钟可能已经取出上一激活段的回调, generation 阻止它借用新一段订阅多跑 loader
        if (generation != polling.generation) return;
        this.reapDownstream();
        if (this.entryCount() == 0) return;
        this.dirty();
    }

    @Override
    public void dirty() {
        if (this.loadingThread == Thread.currentThread()) {
            throw new IllegalStateException("Reentrant invalidation: the loader invalidated this signal while it was still running");
        }
        while (true) {
            if (this.isRetired()) {
                return;
            }
            int current = this.loadState.get();
            if (current == UNLOADED || current == IDLE) {
                if (this.loadState.compareAndSet(current, LOADING)) {
                    this.scheduleLoad(current);
                    return;
                }
            } else if (current == LOADING) {
                if (this.loadState.compareAndSet(LOADING, LOADING_DIRTY)) {
                    return;
                }
            } else {
                return;
            }
        }
    }

    // 提交加载任务. 执行器拒绝时恢复原状态, 最终重试窗口内的并发失效可能需要再次 dirty().
    private void scheduleLoad(int rollbackState) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < MAX_SCHEDULE_ATTEMPTS; attempt++) {
            RuntimeException rejection = this.submit();
            if (rejection == null) {
                // 重试成功后仍上报本轮已经发生的拒绝
                if (failure != null) {
                    SparrowUI.getInstance().handleException("Failed to schedule an async signal load", failure);
                }
                return;
            }
            failure = ThrowableUtils.combine(failure, rejection);
            // 拒绝窗口内登记了失效时再尝试一次
            if (!this.loadState.compareAndSet(LOADING_DIRTY, LOADING)) {
                break;
            }
        }
        // 最终恢复到可再次调度的状态
        this.loadState.set(rollbackState);
        SparrowUI.getInstance().handleException("Failed to schedule an async signal load", failure);
    }

    private RuntimeException submit() {
        try {
            this.executor.execute(this::load);
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private void load() {
        if (this.isRetired()) return;

        boolean changed = false;
        RuntimeException failure = null;
        boolean pending;
        try {
            changed = this.publishValue(this.runLoader());
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            // 先记录完成时刻再发布 IDLE, 激活方看到空闲时也能看到这次时间
            PollingState polling = this.polling;
            if (polling != null) polling.lastCompletedNanos = System.nanoTime();
            pending = this.loadState.getAndUpdate(current -> current == LOADING_DIRTY ? LOADING : IDLE) == LOADING_DIRTY;
        }

        if (pending && !this.isRetired()) {
            this.scheduleLoad(IDLE);
        }
        if (changed) {
            try {
                this.notifyDirty();
            } catch (RuntimeException exception) {
                failure = ThrowableUtils.combine(failure, exception);
            }
        }
        // loader 与失效派发都在执行器线程, 失败在此边界上报
        if (failure != null) {
            SparrowUI.getInstance().handleException("Failed to load an async signal value", failure);
        }
    }

    // 记录 loader 所在线程, 供 dirty() 拒绝装载函数造成的自失效
    private T runLoader() {
        this.loadingThread = Thread.currentThread();
        try {
            return this.loader.get();
        } finally {
            this.loadingThread = null;
        }
    }

    private boolean publishValue(T value) {
        while (true) {
            Versioned<T> current = this.state.get();
            if (same(this.sameValue, current.value(), value)) {
                return false;
            }
            if (this.state.compareAndSet(current, new Versioned<>(value, current.version() + 1))) {
                return true;
            }
        }
    }

    // 每个轮询分区独立保存激活状态, Polling 设置可由同一个 KeyedSignal 的全部分区共享
    private static final class PollingState {
        private final Polling settings;
        private volatile long lastCompletedNanos;           // 最近一次装载结束时间, 成功失败都记录
        @Nullable private Subscription clockSubscription;   // 有订阅期间挂在轮询时钟上, activationLock 内读写
        private volatile long generation;                   // 每次挂时钟递增, activationLock 内写

        private PollingState(Polling settings) {
            this.settings = settings;
        }
    }

    // 轮询设置由同周期来源共享, periodNanos 用于判断重新激活时是否需要补载
    record Polling(AbstractSignal<Long> clock, long periodNanos) {

        @NotNull
        static Polling everyTicks(long periodTicks) {
            return new Polling(require(Signals.everyTicks(periodTicks)), TimeUnit.MILLISECONDS.toNanos(periodTicks * MILLIS_PER_TICK));
        }

        @NotNull
        static Polling everyMillis(long periodMillis) {
            return new Polling(require(Signals.everyMillis(periodMillis)), TimeUnit.MILLISECONDS.toNanos(periodMillis));
        }
    }
}
