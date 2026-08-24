package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

/**
 * 防抖与节流共用的定时派生节点.
 * <p>有订阅期间读取上次通知时保存的值. 没有订阅时透传上游, 不建立订阅或延时任务.
 *
 * @param <T> 值类型
 */
abstract sealed class PacedSignal<T> extends AbstractSignal<T> permits DebounceSignal, ThrottleSignal {
    private final AbstractSignal<T> source;
    private final long delay;
    private final Delayer delayer;
    private final Object stateLock = new Object();

    @Nullable private volatile Snapshot<T> snapshot;    // 最近一次通知或无订阅拉取时保存的值
    private volatile boolean active;
    private Subscription upstream;
    @Nullable private Delayer.Handle pending;           // 状态锁内读写
    private long generation;                            // 每次排入与停表都递增, 用于拒绝被替换或跨激活段的迟到任务

    PacedSignal(AbstractSignal<T> source, long delay, Delayer delayer) {
        this.source = source;
        this.delay = delay;
        this.delayer = delayer;
    }

    @Override
    public T get() {
        return this.current().value();
    }

    @Override
    long version() {
        return this.current().version();
    }

    // 有订阅期间读取稳定值, 无订阅时对齐到上游当前版本
    private Snapshot<T> current() {
        if (this.active) {
            Snapshot<T> snapshot = this.snapshot;
            assert snapshot != null; // onActive 先拍基线再置 active
            return snapshot;
        }
        return this.align();
    }

    private Snapshot<T> align() {
        Snapshot<T> current = this.snapshot;
        if (current != null && current.sourceVersion() == this.source.version()) {
            return current;
        }
        synchronized (this.stateLock) {
            // 并发激活已经建立基线时直接使用
            if (!this.active && this.sourceChangedLocked()) {
                this.captureLocked();
            }
            current = this.snapshot;
            assert current != null;
            return current;
        }
    }

    @Override
    protected void onActive() {
        // 先挂上游再建立基线, 订阅前的变化并入基线且不补发
        this.upstream = this.linkTo(this.source, this::onSourceDirty);
        try {
            synchronized (this.stateLock) {
                if (this.sourceChangedLocked()) {
                    this.captureLocked();
                }
                this.active = true;
            }
        } catch (RuntimeException | Error exception) {
            // 建立基线失败时撤销上游订阅, 配合 register 回滚
            this.upstream.close();
            this.upstream = null;
            throw exception;
        }
    }

    private void onSourceDirty() {
        boolean emit;
        synchronized (this.stateLock) {
            if (!this.active) return;
            emit = this.onSourceDirtyLocked();
        }
        if (emit) this.notifyDirty();
    }

    // 延时任务在调度线程触发. generation 隔离被替换和上一激活段的迟到任务, 用户求值异常在此边界上报.
    private void fire(long generation) {
        try {
            this.reapDeadEntries();
            boolean emit;
            synchronized (this.stateLock) {
                if (!this.active || generation != this.generation) return;
                this.pending = null;
                emit = this.onFireLocked();
            }
            if (emit) this.notifyDirty();
        } catch (RuntimeException exception) {
            SparrowUI.getInstance().handleException("Failed to fire a paced signal task", exception);
        }
    }

    // 上游失效时在状态锁内决定是否立即派发
    abstract boolean onSourceDirtyLocked();

    // 延时任务到点时在状态锁内决定是否派发
    abstract boolean onFireLocked();

    // 在状态锁内清理当前激活段的子类状态
    void onInactiveLocked() {
    }

    // 是否还有任务等着触发. 状态锁内调用.
    final boolean waitingLocked() {
        return this.pending != null;
    }

    // 上游版本是否已经越过快照. 状态锁内调用.
    final boolean sourceChangedLocked() {
        Snapshot<T> current = this.snapshot;
        return current == null || current.sourceVersion() != this.source.version();
    }

    // 保存上游当前值并推进版本. 先读版本再读值, 并发变化会留下偏旧版本, 下一次拉取会再次对齐.
    final void captureLocked() {
        long sourceVersion = this.source.version();
        Snapshot<T> current = this.snapshot;
        this.snapshot = new Snapshot<>(this.source.get(), sourceVersion, current == null ? 1L : current.version() + 1L);
    }

    // 排入新任务后再取消旧任务. 调度失败时 generation、pending 与旧任务都保持原样.
    final void scheduleLocked() {
        long next = this.generation + 1L;
        Delayer.Handle scheduled = this.delayer.schedule(() -> this.fire(next), this.delay);
        Delayer.Handle previous = this.pending;
        this.pending = scheduled;
        this.generation = next;
        if (previous != null) {
            previous.cancel();
        }
    }

    @Override
    protected void onInactive() {
        Delayer.Handle pending;
        synchronized (this.stateLock) {
            this.active = false;
            pending = this.pending;
            this.pending = null;
            // 先推进 generation, 让未能及时取消的任务无法进入下一激活段
            this.generation++;
            this.onInactiveLocked();
        }
        if (pending != null) {
            pending.cancel();
        }
        this.upstream.close();
        this.upstream = null;
    }

    private record Snapshot<V>(V value, long sourceVersion, long version) {
    }
}
