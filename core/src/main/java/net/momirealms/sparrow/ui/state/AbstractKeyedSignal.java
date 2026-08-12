package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KeyedSignal 抽象实现骨架.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 * @param <P> 分区实现类型
 */
abstract class AbstractKeyedSignal<K, T, P extends AbstractSignal<T>> implements KeyedSignal<K, T> {
    private final ConcurrentHashMap<K, P> partitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, HandleReference<K, T>> handles = new ConcurrentHashMap<>();
    private final ReferenceQueue<PartitionHandle<K, T>> deadHandles = new ReferenceQueue<>();

    /**
     * 创建一个新分区实现.
     * 在 {@code computeIfAbsent} 的函数内被调用.
     */
    abstract P createPartition(K key);

    /**
     * 标脏一个已激活的分区.
     */
    abstract void dirtyPartition(P partition);

    /**
     * 取出或新建一个 Key 对应的分区.
     */
    final P partition(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        this.purgeDeadHandles();
        P partition = this.partitions.computeIfAbsent(key, k -> {
            // 创建分区并接到该 key 已有的 PartitionHandle 上.
            P created = this.createPartition(key);
            PartitionHandle<K, T> handle = this.liveHandle(key);
            if (handle != null) {
                handle.attach(created);
            }
            return created;
        });
        this.afterPartitionAccess(partition);
        return partition;
    }

    /**
     * 分区被取用后的回调, 在 {@code computeIfAbsent} 返回之后执行.
     * <p>本回调对<strong>每次</strong>取用分区都会调用, 因此实现必须幂等.
     */
    void afterPartitionAccess(P partition) {
    }

    @Override
    public T get(@NotNull K key) {
        return this.partition(key).get();
    }

    /**
     * 返回的是持有分区本身的 {@link PartitionHandle} 句柄实现, 跨越删除重建后持续有效.
     * 缓存是弱的, 句柄只在调用方或某条绑定还持有它时存活, 因此只读取过而没有绑定过的 key 不留痕迹.
     * 删除释放的是分区及其缓存值.
     */
    @Override
    @NotNull
    public Signal<T> at(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        this.purgeDeadHandles();
        PartitionHandle<K, T> handle = this.handle(key);
        // 句柄可能晚于分区出现(先 get 后 at), 那种情况下分区创建时还找不到句柄, 由这里补挂.
        P partition = this.partitions.compute(key, (ignored, existing) -> {
            P target = existing != null ? existing : this.createPartition(key);
            handle.attach(target);
            return target;
        });
        this.afterPartitionAccess(partition);
        return handle;
    }

    /**
     * 取出或新建 key 对应的句柄.
     * <p>句柄按弱引用缓存: 强引用只来自调用方拿到的返回值, 以及绑定凭证里那条通往句柄的链路.
     * 两者都不在了就说明没人再关心这个 key 的视图, 句柄随之回收.
     */
    private PartitionHandle<K, T> handle(K key) {
        AtomicReference<PartitionHandle<K, T>> resolved = new AtomicReference<>();
        this.handles.compute(key, (mapKey, existing) -> {
            PartitionHandle<K, T> current = existing == null ? null : existing.get();
            if (current != null) {
                resolved.set(current);
                return existing;
            }
            PartitionHandle<K, T> created = new PartitionHandle<>(this, mapKey);
            // 强引用要逃出映射函数, 否则刚建好的句柄可能在返回给调用方之前就被回收.
            resolved.set(created);
            return new HandleReference<>(created, mapKey, this.deadHandles);
        });
        return resolved.get();
    }

    // 返回该 key 当前仍存活的句柄, 没有则返回 null.
    @Nullable
    private PartitionHandle<K, T> liveHandle(K key) {
        HandleReference<K, T> reference = this.handles.get(key);
        return reference == null ? null : reference.get();
    }

    // 删除句柄里已被回收的映射.
    private void purgeDeadHandles() {
        Reference<? extends PartitionHandle<K, T>> reference;
        while ((reference = this.deadHandles.poll()) != null) {
            if (reference instanceof HandleReference<?, ?> dead) {
                // 只在映射仍指向这条死引用时删除, 免得误删同 key 新建的句柄.
                this.handles.remove(dead.key, dead);
            }
        }
    }

    @Override
    public void dirty(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        P partition = this.partitions.get(key);
        if (partition != null) {
            this.dirtyPartition(partition);
        }
    }

    @Override
    public void dirtyAll() {
        for (P partition : this.partitions.values()) {
            this.dirtyPartition(partition);
        }
    }

    @Override
    public void remove(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        this.purgeDeadHandles();
        this.partitions.computeIfPresent(key, (ignored, partition) -> {
            // 分区终止会关闭它的全部订阅.
            partition.retire();
            PartitionHandle<K, T> handle = this.liveHandle(key);
            if (handle != null) {
                handle.onPartitionEvicted(partition);
            }
            return null;
        });
    }

    /**
     * 当前已激活的分区数.
     */
    final int partitionCount() {
        return this.partitions.size();
    }

    /**
     * 当前仍存活的视图数.
     */
    final int viewCount() {
        this.purgeDeadHandles();
        int count = 0;
        for (HandleReference<K, T> reference : this.handles.values()) {
            if (reference.get() != null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void clear() {
        for (K key : this.partitions.keySet()) {
            this.remove(key);
        }
    }

    /**
     * 句柄的弱引用, 携带 key 以便句柄回收后从表里摘掉对应映射.
     */
    private static final class HandleReference<K, T> extends WeakReference<PartitionHandle<K, T>> {
        private final K key;

        private HandleReference(PartitionHandle<K, T> handle, K key, ReferenceQueue<? super PartitionHandle<K, T>> queue) {
            super(handle, queue);
            this.key = key;
        }
    }
}
