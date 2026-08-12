package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link KeyedSignal#at} 返回的分区句柄: 只做转发, 跨越分区的删除重建持续有效.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 */
final class PartitionHandle<K, T> extends AbstractSignal<T> {
    private final AbstractKeyedSignal<K, T, ?> owner;
    private final K key;
    private final AtomicLong version = new AtomicLong();
    private final Object attachLock = new Object();
    private volatile AbstractSignal<T> attached;   // 当前已挂钩的分区, 让 attach 幂等, owner 的快路径无锁读取
    private Subscription forward;                  // 到 attached 的转发凭证, 与 attached 一起换

    PartitionHandle(AbstractKeyedSignal<K, T, ?> owner, K key) {
        this.owner = owner;
        this.key = key;
    }

    @Override
    public T get() {
        return this.owner.partition(this.key).get();
    }

    /**
     * <p>版本由句柄自己维护并单调递增, <strong>不能透传分区版本</strong>.
     */
    @Override
    long version() {
        return this.version.get();
    }

    /**
     * 检查本句柄当前是否正挂在该分区上.
     * <p>与挂载或驱逐并发时结果可能立刻过期: 误判为否只是多走一次慢路径,
     * 判为是则该挂载关系真实存在过, 等价于取用发生在换挂之前.
     */
    boolean isAttachedTo(AbstractSignal<T> partition) {
        return this.attached == partition;
    }

    /**
     * 建立分区到本句柄的转发, 换挂时自己关掉上一条.
     * <p>只允许在 owner 中该 key 的 compute 内调用, 从而与驱逐互相串行;
     * {@link #isAttachedTo} 的语义依赖这一点.
     */
    void attach(AbstractSignal<T> partition) {
        Subscription previous;
        synchronized (this.attachLock) {
            if (this.attached == partition) {
                return;
            }
            previous = this.forward;
            // 这里使用弱订阅, 不能让分区反过来钉住本对象.
            this.forward = partition.onDirty(this::onPartitionDirty);
            this.version.incrementAndGet();
            // attached 是挂载完全完成的发布标志, 必须在转发建立与版本推进之后最后写入.
            this.attached = partition;
        }
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * 分区被删除时调用, 同样只允许在该 key 的 compute 内.
     *
     * @param evicted 需要被删除的分区
     */
    void onPartitionEvicted(AbstractSignal<T> evicted) {
        Subscription previous;
        synchronized (this.attachLock) {
            // 只有被删除的是当前挂着的那一个才删除
            if (this.attached != evicted) return;
            previous = this.forward;
            this.attached = null;
            this.forward = null;
            this.version.incrementAndGet();
        }
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * 当分区标脏时, 句柄也推进一次版本.
     */
    private void onPartitionDirty() {
        this.version.incrementAndGet();
        this.notifyDirty();
    }
}
