package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link KeyedSignal#at} 返回的分区句柄: 只做转发, 跨越分区的删除重建持续有效.
 * <p>到分区的转发只在句柄自己有订阅者期间存在, 所以分区只在 "有人订阅了它的句柄" 时才算有订阅;
 * 没人订阅时句柄靠拉取对齐版本, 纯拉取的下游照样读到新值.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 */
sealed class PartitionHandle<K, T> extends AbstractSignal<T> permits MutablePartitionHandle {
    private final AbstractKeyedSignal<K, T, ?> owner;
    private final K key;
    private final AtomicReference<Synced<T>> synced = new AtomicReference<>(new Synced<>(null, 0L, 0L));
    private final Object attachLock = new Object();
    private volatile AbstractSignal<T> attached;   // 当前跟着的分区, 让 attach 幂等, owner 的快路径无锁读取
    private boolean active;                        // 有订阅期间为真, attachLock 内读写
    private Subscription forward;                  // 到 attached 的转发凭证, 只在有订阅期间存在, 与 attached 一起换

    PartitionHandle(AbstractKeyedSignal<K, T, ?> owner, K key) {
        this.owner = owner;
        this.key = key;
    }

    K key() {
        return this.key;
    }

    @Override
    public T get() {
        return this.owner.partition(this.key).get();
    }

    /**
     * 版本由句柄自己维护并单调递增, <strong>不能透传分区版本</strong>, 前后两个分区各有各的计数.
     * <p>读版本时与当前分区对一次, 没人订阅期间分区的变化由这里收进版本.
     */
    @Override
    long version() {
        AbstractSignal<T> partition = this.attached;
        return partition == null ? this.synced.get().version() : this.sync(partition).version();
    }

    // 把自己的版本对齐到分区当前版本, 分区换了或分区版本变了就推进一次. 争用时只从读到的那一份往前推, 输了就重来.
    private Synced<T> sync(AbstractSignal<T> partition) {
        while (true) {
            long partitionVersion = partition.version();
            Synced<T> current = this.synced.get();
            if (current.partition() == partition && current.partitionVersion() == partitionVersion) {
                return current;
            }
            Synced<T> next = new Synced<>(partition, partitionVersion, current.version() + 1L);
            if (this.synced.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    /**
     * 检查本句柄当前是否正跟着该分区.
     * <p>与挂载或驱逐并发时结果可能立刻过期: 误判为否只是多走一次慢路径,
     * 判为是则该挂载关系真实存在过, 等价于取用发生在换挂之前.
     */
    boolean isAttachedTo(AbstractSignal<T> partition) {
        return this.attached == partition;
    }

    /**
     * 跟上一个分区, 有订阅者时一并建立它到本句柄的转发, 换挂时自己关掉上一条.
     * <p>只允许在 owner 中该 key 的 compute 内调用, 从而与驱逐互相串行.
     */
    void attach(AbstractSignal<T> partition) {
        Subscription previous;
        synchronized (this.attachLock) {
            if (this.attached == partition) {
                return;
            }
            previous = this.forward;
            // 这里使用弱订阅, 不能让分区反过来钉住本对象.
            this.forward = this.active ? partition.onDirty(this::onPartitionDirty) : null;
            this.sync(partition);
            // attached 是挂载完全完成的发布标志, 必须在转发建立与版本推进之后最后写入.
            this.attached = partition;
        }
        if (previous != null) {
            previous.close();
        }
    }

    // 分区被删除时调用, 同样只允许在该 key 的 compute 内.
    void onPartitionEvicted(AbstractSignal<T> evicted) {
        Subscription previous;
        synchronized (this.attachLock) {
            // 只有被驱逐的正是当前跟着的分区时才摘除
            if (this.attached != evicted) return;
            previous = this.forward;
            this.attached = null;
            this.forward = null;
            // 值将来自重建的分区, 版本先推进; 记录里不再攥着已退役的分区
            this.synced.updateAndGet(current -> new Synced<>(null, 0L, current.version() + 1L));
        }
        if (previous != null) {
            previous.close();
        }
    }

    @Override
    protected void onActive() {
        synchronized (this.attachLock) {
            this.active = true;
            AbstractSignal<T> partition = this.attached;
            if (partition != null) {
                // 先挂转发再对版本, 挂载前后到达的失效都不会漏; 订阅前的变化收进版本, 不补发
                this.forward = partition.onDirty(this::onPartitionDirty);
                this.sync(partition);
            }
        }
    }

    // 分区标脏时句柄也推进一次版本并转发.
    private void onPartitionDirty() {
        AbstractSignal<T> partition = this.attached;
        if (partition != null) {
            this.sync(partition);
        }
        this.notifyDirty();
    }

    @Override
    protected void onInactive() {
        Subscription previous;
        synchronized (this.attachLock) {
            this.active = false;
            previous = this.forward;
            this.forward = null;
        }
        if (previous != null) {
            previous.close();
        }
    }

    // 最近一次对齐时跟着的分区与它的版本, 以及句柄自己的版本.
    private record Synced<V>(@Nullable AbstractSignal<V> partition, long partitionVersion, long version) {
    }
}
