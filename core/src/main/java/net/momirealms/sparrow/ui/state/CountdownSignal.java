package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 倒计时节点, 值是距截止时刻还剩的毫秒数, 不小于 0. 截止是墙钟毫秒, 时钟只决定采样节奏.
 * <p>有订阅且还没归零时挂着采样时钟, 归零那一拍发最后一次通知并把时钟摘掉; 没有订阅时不挂时钟, 读值实时算.
 */
final class CountdownSignal extends AbstractSignal<Long> {
    private final AbstractSignal<Long> deadline;
    private final AbstractSignal<Long> clock;
    private final Object stateLock = new Object();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(null, -1L, 0L, 0L));
    private volatile boolean active;                    // 有订阅期间为真, 拉取路径据此决定读记录还是对齐
    private Subscription deadlineSubscription;          // 有订阅期间挂着
    @Nullable private Subscription clockSubscription;   // 只在有订阅且还没归零期间挂着, stateLock 内读写

    CountdownSignal(AbstractSignal<Long> deadline, AbstractSignal<Long> clock) {
        this.deadline = deadline;
        this.clock = clock;
    }

    @Override
    public Long get() {
        return remaining(this.current().deadlineMillis());
    }

    @Override
    long version() {
        return this.current().version();
    }

    private Snapshot current() {
        return this.active ? this.snapshot.get() : this.align();
    }

    // 无订阅时的拉取路径, 截止变了就重读, 剩余变了就推进版本, 派生节点的缓存才会随时间失配.
    private Snapshot align() {
        while (true) {
            Snapshot current = this.snapshot.get();
            long deadlineVersion = this.deadline.version();
            Long deadlineMillis = deadlineVersion == current.deadlineVersion() ? current.deadlineMillis() : this.deadline.get();
            long remaining = remaining(deadlineMillis);
            if (deadlineVersion == current.deadlineVersion() && remaining == current.remaining()) {
                return current;
            }
            Snapshot next = new Snapshot(deadlineMillis, deadlineVersion, remaining, current.version() + 1L);
            if (this.snapshot.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    @Override
    protected void onActive() {
        this.deadlineSubscription = this.deadline.link(this, this::onDeadlineDirty);
        try {
            synchronized (this.stateLock) {
                long remaining = this.refreshDeadlineLocked();
                this.active = true;
                this.updateSamplingLocked(remaining);
            }
        } catch (RuntimeException | Error exception) {
            // 读截止抛出时撤销挂载, 让 register 的回滚留下干净现场
            this.deadlineSubscription.close();
            this.deadlineSubscription = null;
            throw exception;
        }
    }

    // 截止失效, 重读一次并按新的剩余决定采不采样.
    private void onDeadlineDirty() {
        synchronized (this.stateLock) {
            if (!this.active) return;
            this.updateSamplingLocked(this.refreshDeadlineLocked());
        }
        this.notifyDirty();
    }

    // 采样时钟到拍, 用记下的截止算一次剩余. 每拍都派发, 死掉的下游由派发自己剔除, 清到空会走 onInactive 把时钟摘掉.
    private void onSample() {
        synchronized (this.stateLock) {
            if (!this.active || this.clockSubscription == null) return;
            long remaining = remaining(this.snapshot.get().deadlineMillis());
            this.snapshot.updateAndGet(current -> new Snapshot(current.deadlineMillis(), current.deadlineVersion(), remaining, current.version() + 1L));
            this.updateSamplingLocked(remaining);
        }
        this.notifyDirty();
    }

    // <strong>有订阅期间读截止只在这里</strong>, 采样一律用记下的那份, 截止来自分区句柄时才不会给已驱逐的 key 重建分区.
    private long refreshDeadlineLocked() {
        long deadlineVersion = this.deadline.version();
        Long deadlineMillis = this.deadline.get();
        long remaining = remaining(deadlineMillis);
        this.snapshot.updateAndGet(current -> new Snapshot(deadlineMillis, deadlineVersion, remaining, current.version() + 1L));
        return remaining;
    }

    // 还没归零才挂采样时钟, 归零就摘. stateLock 内调用.
    private void updateSamplingLocked(long remaining) {
        if (remaining > 0L) {
            if (this.clockSubscription == null) {
                this.clockSubscription = this.clock.link(this, this::onSample);
            }
        } else if (this.clockSubscription != null) {
            this.clockSubscription.close();
            this.clockSubscription = null;
        }
    }

    @Override
    protected void onInactive() {
        synchronized (this.stateLock) {
            this.active = false;
            this.updateSamplingLocked(0L);
        }
        this.deadlineSubscription.close();
        this.deadlineSubscription = null;
    }

    // 截止为 null 按已到期算.
    private static long remaining(@Nullable Long deadlineMillis) {
        return deadlineMillis == null ? 0L : Math.max(0L, deadlineMillis - System.currentTimeMillis());
    }

    // 最近一次记下的截止与它的版本, 最近一次算出的剩余, 以及自己的版本.
    private record Snapshot(@Nullable Long deadlineMillis, long deadlineVersion, long remaining, long version) {
    }
}
