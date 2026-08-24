package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

// 集合装饰器共用版本与 batch 通知逻辑
abstract sealed class CollectionSignal<T> extends AbstractSignal<T> permits ListSignalImpl, SetSignalImpl, MapSignalImpl {
    private final AtomicLong version = new AtomicLong();
    private final ReentrantLock batchLock = new ReentrantLock();
    private boolean batchPending;   // 当前线程的 batch 已发生变更, 只在持锁时读写

    // 批量移除会反复查询实参集合, 将非 Set 转成哈希查找
    @NotNull
    static Collection<?> lookupOf(@NotNull Collection<?> c) {
        return c instanceof Set<?> ? c : new HashSet<>(c);
    }

    public final void batch(@NotNull Runnable changes) {
        this.batchLock.lock();
        try {
            changes.run();
        } finally {
            // changes 抛出时也通知已经落地的变更
            boolean notify = false;
            if (this.batchLock.getHoldCount() == 1) {
                notify = this.batchPending;
                this.batchPending = false;
            }
            this.batchLock.unlock();
            if (notify) {
                this.notifyDirty();
            }
        }
    }

    // 变更落地后推进版本, 当前线程处于 batch 时延后通知
    final void changed() {
        this.version.incrementAndGet();
        if (this.batchLock.isHeldByCurrentThread()) {
            this.batchPending = true;
            return;
        }
        this.notifyDirty();
    }

    @Override
    final long version() {
        return this.version.get();
    }

    // 按身份判等, 防止依赖 signal 身份的弱表与 merging 混淆内容相同的两个包装器
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }
}
