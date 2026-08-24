package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
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
    private static final int MAX_SCHEDULE_ATTEMPTS = 2;     // 首次提交, 外加为并发登记的失效补一次.
    private static final long MILLIS_PER_TICK = 50L;
    // 加载函数与已发布状态
    private final Executor executor;
    private final Supplier<? extends T> loader;
    private final BiPredicate<? super T, ? super T> sameValue;
    private final AtomicReference<Versioned<T>> state;
    private final AtomicInteger loadState = new AtomicInteger(UNLOADED);
    // 当前加载
    @Nullable private Thread loadingThread; // 正在跑装载函数的线程
    @Nullable private final PollingState polling;       // 轮询状态, 为 null 就是普通异步源

    AsyncSignalImpl(T placeholder, Executor executor, Supplier<? extends T> loader, BiPredicate<? super T, ? super T> sameValue, @Nullable Polling polling) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.sameValue = Objects.requireNonNull(sameValue, "sameValue");
        this.state = new AtomicReference<>(new Versioned<>(placeholder, 0L));
        this.polling = polling == null ? null : new PollingState(polling);
    }

    // 调度首次初始化.
    void scheduleInitialLoad() {
        if (this.loadState.compareAndSet(UNLOADED, LOADING)) {
            // 拒绝时必须退回 UNLOADED 而不是 IDLE: 首次调度入口只认 UNLOADED,
            // 退到 IDLE 会让这个分区永远停在占位值上, 后续访问连任务都不再提交.
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
            // 订阅到来时数据已经放了超过一个周期就立刻补一次; 首载没调度过或还在飞时不叠加
            if (this.loadState.get() == IDLE && System.nanoTime() - polling.lastCompletedNanos >= polling.settings.periodNanos()) {
                this.dirty();
            }
        } catch (RuntimeException | Error exception) {
            // 激活刷新抛出时撤销时钟订阅, 让 register 的回滚留下干净现场
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

    // 轮询时钟到拍, 下游全死时这里就是清扫机会, 清到空会走 onInactive 把时钟订阅收掉, 常量数据源也因此能停.
    // 清扫要顺着订阅链走到底: 装载结果判等不变时没有派发, 被持有的派生节点自己发现不了它的订阅者已经死光.
    private void onPollTick(PollingState polling, long generation) {
        // 时钟派发前先把回调读了出来, 这期间停表再重开撤不回那次调用, 迟到的旧段回调看到的订阅数是新一段的, 不拦就多跑一次 loader.
        // 只是一次 volatile 读, 读完到 dirty() 之间恰好停表重开的话仍会多跑一次, 那与新一段激活时的补载同形, 不为它加锁.
        if (generation != polling.generation) return;
        this.reapDownstream();
        if (this.entryCount() == 0) return;
        this.dirty();
    }

    @Override
    @NotNull
    public WeakAsyncControl weakControl() {
        return new Control(this);
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

    // 提交加载任务, 执行器拒绝任务时按当前状态回滚,
    // 极窄窗口内并发登记的失效可能丢失, 下一次 dirty() 会恢复.
    private void scheduleLoad(int rollbackState) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < MAX_SCHEDULE_ATTEMPTS; attempt++) {
            RuntimeException rejection = this.submit();
            if (rejection == null) {
                // 重试成功, 但本轮被拒的那次仍要如实上报.
                if (failure != null) {
                    SparrowUI.getInstance().handleException("Failed to schedule an async signal load", failure);
                }
                return;
            }
            failure = ThrowableUtils.combine(failure, rejection);
            // 期间登记的失效记录下, 完成后进行一次额外的尝试.
            if (!this.loadState.compareAndSet(LOADING_DIRTY, LOADING)) {
                break;
            }
        }
        // 最终回滚到一个可再次调度的状态.
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
            // 只有轮询用得上这个时刻. 先记再改状态, 看到 IDLE 的人一定看得到这次的时刻
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
        // 本方法整个跑在执行器线程上, 抛出去只会落到执行器的未捕获处理器, 或者直接被吞掉.
        if (failure != null) {
            SparrowUI.getInstance().handleException("Failed to load an async signal value", failure);
        }
    }

    // 跑一次装载函数, 期间记下当前线程, 让 dirty() 能认出 loader 在自己脚下拆台.
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

    // 弱持目标的控制句柄, 目标回收之后每个方法都是空操作.
    static final class Control implements WeakAsyncControl {
        private final WeakReference<AsyncSignalImpl<?>> target;

        private Control(AsyncSignalImpl<?> target) {
            this.target = new WeakReference<>(target);
        }

        @Override
        public void dirty() {
            AsyncSignalImpl<?> signal = this.target.get();
            if (signal != null) signal.dirty();
        }

        @Override
        public boolean isStale() {
            return this.target.get() == null;
        }
    }

    // 轮询才用得上的那部分状态, 普通异步源只留一个 null. 每个数据源一份, 里面的设置则是同一个 KeyedSignal 的各分区共用的.
    private static final class PollingState {
        private final Polling settings;
        private volatile long lastCompletedNanos;           // 上一次装载结束的时刻, 成功失败都记, 激活刷新据此判断过没过期
        @Nullable private Subscription clockSubscription;   // 有订阅期间挂在轮询时钟上, activationLock 内读写
        private volatile long generation;                   // 每挂一次时钟推进一次, 时钟回调带着自己那一段的代数, activationLock 内写

        private PollingState(Polling settings) {
            this.settings = settings;
        }
    }

    // 轮询设置, 共享的周期时钟, 以及一个周期折成纳秒(激活刷新用). 同周期的轮询共用同一个时钟, 会在同一拍一起发起装载.
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
