package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 集合装饰器共用的骨架, 变更落地之后推进版本并向订阅者失效, {@code batch} 把本线程期间的变更合并成一次通知.
 *
 * @param <T> 集合类型
 */
abstract sealed class CollectionSignal<T> extends AbstractSignal<T> permits ListSignalImpl, SetSignalImpl, MapSignalImpl {
    private final AtomicLong version = new AtomicLong();
    private final ReentrantLock batchLock = new ReentrantLock();
    private boolean batchPending;   // 本线程 batch 期间攒下了变更, 只在持有 batchLock 时读写

    /**
     * 把 {@code changes} 期间本线程对本集合的变更合并成一次通知, 嵌套时只有最外层通知.
     * <p>只合并本线程的变更, 别的线程这期间的变更照常各自通知. {@code changes} 抛出时已经落地的变更保留并仍通知一次.
     * 通知在 {@code changes} 返回之后发出, 期间的变更一定被覆盖到, 至多多发一次.
     *
     * @param changes 要合并的一批变更
     */
    public final void batch(@NotNull Runnable changes) {
        this.batchLock.lock();
        try {
            changes.run();
        } finally {
            // 抛出时也要通知, 已经落地的变更不能没人知道
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

    // 一次有效变更落地之后调用. 在 batch 里只记下, 否则当场向下游失效.
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

    // 有意按身份判等. 内容判等会让按 signal 存条目的 WeakHashMap 与按成员数组比较的 merging 把两个内容相同的包装器当成一个.
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }
}
