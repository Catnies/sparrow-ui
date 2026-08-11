package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.util.ThrowableUtils;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

final class AsyncSignalImpl<T> extends AbstractSignal<T> implements AsyncSignal<T> {
    private static final int UNLOADED = 0;
    private static final int IDLE = 1;
    private static final int LOADING = 2;
    private static final int LOADING_DIRTY = 3;
    private static final int MAX_SCHEDULE_ATTEMPTS = 2;     // 首次提交, 外加为并发登记的失效补一次.

    private final Executor executor;
    private final Supplier<? extends T> loader;
    private final AtomicReference<Versioned<T>> state;
    private final AtomicInteger loadState = new AtomicInteger(UNLOADED);

    AsyncSignalImpl(T placeholder, Executor executor, Supplier<? extends T> loader) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.state = new AtomicReference<>(new Versioned<>(placeholder, 0L));
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
    public void dirty() {
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

    // 提交加载任务, 执行器拒绝时按当前状态回滚.
    private void scheduleLoad(int rollbackState) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < MAX_SCHEDULE_ATTEMPTS; attempt++) {
            RuntimeException rejection = this.submit();
            if (rejection == null) {
                if (failure == null) {
                    return;
                }
                // 重试成功, 但本轮调度的拒绝仍要如实告知调用方.
                throw failure;
            }
            failure = ThrowableUtils.combine(failure, rejection);
            // 期间登记的失效记录下, 完成后进行一次额外的尝试.
            if (!this.loadState.compareAndSet(LOADING_DIRTY, LOADING)) {
                break;
            }
        }
        // 最终回滚到一个可再次调度的状态.
        this.loadState.set(rollbackState);
        throw failure;
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
            changed = this.publishValue(this.loader.get());
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            pending = this.loadState.getAndUpdate(current -> current == LOADING_DIRTY ? LOADING : IDLE) == LOADING_DIRTY;
        }

        if (pending && !this.isRetired()) {
            try {
                this.scheduleLoad(IDLE);
            } catch (RuntimeException exception) {
                failure = ThrowableUtils.combine(failure, exception);
            }
        }
        if (changed) {
            try {
                this.notifyDirty();
            } catch (RuntimeException exception) {
                failure = ThrowableUtils.combine(failure, exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private boolean publishValue(T value) {
        while (true) {
            Versioned<T> current = this.state.get();
            if (Objects.equals(current.value(), value)) {
                return false;
            }
            if (this.state.compareAndSet(current, new Versioned<>(value, current.version() + 1))) {
                return true;
            }
        }
    }
}
