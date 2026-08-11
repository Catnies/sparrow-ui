package net.momirealms.sparrow.ui.state;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link KeyedSignal#at} 返回的分区视图: 一个只做转发的稳定句柄, 生命周期与 key 绑定.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 */
final class PartitionHandle<K, T> extends AbstractSignal<T> {
    private final AbstractKeyedSignal<K, T, ?> owner;
    private final K key;
    private final AtomicLong version = new AtomicLong();
    private final Object attachLock = new Object();
    private AbstractSignal<T> attached; // 当前已挂钩的分区, 用于让 attach 幂等

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
     * 建立分区到本句柄的转发, 该订阅随分区删除一同关闭.
     */
    void attach(AbstractSignal<T> partition) {
        synchronized (this.attachLock) {
            if (this.attached == partition) {
                return;
            }
            this.attached = partition;
            partition.onDirty(this::onPartitionDirty);
        }
    }

    /**
     * 删除分区时调用, 句柄推进版本.
     */
    void onPartitionEvicted() {
        synchronized (this.attachLock) {
            this.attached = null;
        }
        this.version.incrementAndGet();
    }

    /**
     * 当分区标脏时, 句柄也推进一次版本.
     */
    private void onPartitionDirty() {
        this.version.incrementAndGet();
        this.notifyDirty();
    }
}
