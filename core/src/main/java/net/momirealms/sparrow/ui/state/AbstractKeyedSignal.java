package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KeyedSignal 抽象实现骨架.
 * <p>{@link #at(Object)} 返回的不是分区本身而是 {@link PartitionHandle}:  句柄按 key 缓存并与 key 同生命周期, 因此每个访问过的 key
 * 会保留一个轻量句柄对象; 驱逐释放的是分区及其缓存值.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 * @param <P> 分区实现类型
 */
abstract class AbstractKeyedSignal<K, T, P extends AbstractSignal<T>> implements KeyedSignal<K, T> {
    private final ConcurrentHashMap<K, P> partitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, PartitionHandle<K, T>> handles = new ConcurrentHashMap<>();

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
        P partition = this.partitions.computeIfAbsent(key, k -> {
            // 创建分区并接到该 key 已有的 {@link PartitionHandle} 上.
            P created = this.createPartition(key);
            PartitionHandle<K, T> handle = this.handles.get(key);
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
     * 返回 {@link PartitionHandle} 视图实现, 跨越删除重建后持续有效.
     */
    @Override
    @NotNull
    public Signal<T> at(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        PartitionHandle<K, T> handle = this.handles.computeIfAbsent(key, ignored -> new PartitionHandle<>(this, key));
        // 句柄可能晚于分区出现(先 get 后 at), 那种情况下分区创建时还找不到句柄, 由这里补挂.
        P partition = this.partitions.compute(key, (ignored, existing) -> {
            P target = existing != null ? existing : this.createPartition(key);
            handle.attach(target);
            return target;
        });
        this.afterPartitionAccess(partition);
        return handle;
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
        this.partitions.computeIfPresent(key, (ignored, partition) -> {
            // 分区终止会关闭它的全部订阅.
            partition.retire();
            PartitionHandle<K, T> handle = this.handles.get(key);
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
     * 当前已创建的视图数.
     */
    final int viewCount() {
        return this.handles.size();
    }

    @Override
    public void clear() {
        for (K key : this.partitions.keySet()) {
            this.remove(key);
        }
    }
}
