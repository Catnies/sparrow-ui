package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

/**
 * 按时间安排发出的派生节点, 防抖与节流共用的骨架.
 * <p>有订阅期间 {@code get()} 返回上一次发出时从上游拍下的快照, 下游在两次发出之间看到稳定值;
 * 没有订阅时退化为透传, 不挂上游也不占调度任务, 拉取路径发现上游版本变了就重新拍快照.
 *
 * @param <T> 值类型
 */
abstract sealed class PacedSignal<T> extends AbstractSignal<T> permits DebounceSignal, ThrottleSignal {
    private final AbstractSignal<T> source;
    private final long delay;
    private final Delayer delayer;
    private final Object stateLock = new Object();

    @Nullable private volatile Snapshot<T> snapshot;    // 最近一次发出, 或无订阅时最近一次拉取拍下的快照
    private volatile boolean active;                    // 有订阅期间为真, 拉取路径据此决定读快照还是透传
    private Subscription upstream;                      // 有订阅期间挂着
    @Nullable private Delayer.Handle pending;           // 还没触发的延时任务, 状态锁内读写
    private long generation;                            // 每排一个任务推进一次, 任务带着代数触发, 不符即已被取代

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

    // 有订阅期间只读快照, 无订阅时把快照对齐到上游当前版本.
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
            // 刚被激活的话基线已经拍好, 直接用
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
        // 先挂上游再拍基线, 挂载前后到达的失效都不会漏; 订阅前的变化并进基线, 不补发
        this.upstream = this.source.link(this, this::onSourceDirty);
        try {
            synchronized (this.stateLock) {
                if (this.sourceChangedLocked()) {
                    this.captureLocked();
                }
                this.active = true;
            }
        } catch (RuntimeException | Error exception) {
            // 读上游抛出时撤销挂载, 让 register 的回滚留下干净现场
            this.upstream.close();
            this.upstream = null;
            throw exception;
        }
    }

    private void onSourceDirty() {
        // 下游全死时这里就是清扫机会, 清到空会走 onInactive 把任务与上游一起收掉
        this.reapDeadEntries();
        boolean emit;
        synchronized (this.stateLock) {
            if (!this.active) return;
            emit = this.onSourceDirtyLocked();
        }
        if (emit) this.notifyDirty();
    }

    // 延时任务到点. 代数不符说明这个任务已被后来的排入取代, 什么也不做.
    private void fire(long generation) {
        this.reapDeadEntries();
        boolean emit;
        synchronized (this.stateLock) {
            if (!this.active || generation != this.generation) return;
            this.pending = null;
            emit = this.onFireLocked();
        }
        if (emit) this.notifyDirty();
    }

    // 上游失效时在状态锁内决定怎么办, 返回 true 表示现在就向下游发出.
    abstract boolean onSourceDirtyLocked();

    // 延时任务到点时在状态锁内决定怎么办, 返回 true 表示向下游发出.
    abstract boolean onFireLocked();

    // 是否还有任务等着触发. 状态锁内调用.
    final boolean waitingLocked() {
        return this.pending != null;
    }

    // 上游版本是否已经越过快照. 状态锁内调用.
    final boolean sourceChangedLocked() {
        Snapshot<T> current = this.snapshot;
        return current == null || current.sourceVersion() != this.source.version();
    }

    // 从上游拍一份快照并推进版本. 先读版本再读值, 两次读之间上游又变了的话记下的版本偏旧, 下次拉取会再算一遍. 状态锁内调用.
    final void captureLocked() {
        long sourceVersion = this.source.version();
        Snapshot<T> current = this.snapshot;
        this.snapshot = new Snapshot<>(this.source.get(), sourceVersion, current == null ? 1L : current.version() + 1L);
    }

    // 排一个 delay 之后触发的任务, 顶掉还没触发的那个. 排不进去就整笔作废, 代数与旧任务都不动. 状态锁内调用.
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
