package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Objects;
 import java.util.concurrent.atomic.AtomicReference;

/**
 * KeyedSignal 抽象实现骨架.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 * @param <P> 分区实现类型
 */
abstract class AbstractKeyedSignal<K, T, P extends AbstractSignal<T>> implements KeyedSignal<K, T> {
    private final KeyStateStore<K, KeyState<K, T, P>> store;    // key -> (分区 & 句柄)
    private final ReferenceQueue<PartitionHandle<K, T>> deadHandles = new ReferenceQueue<>();

    AbstractKeyedSignal(KeyStateStore<K, KeyState<K, T, P>> store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 创建一个新分区实现.
     * 在该 key 的 compute 函数内被调用.
     */
    abstract P createPartition(K key);

    /**
     * 标脏一个已激活的分区.
     */
    abstract void dirtyPartition(P partition);

    // 取出或新建一个 Key 对应的分区.
    final P partition(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        this.purgeDeadHandles();
        // 读值路径上绝大多数取用命中已有分区, 这条快路径避开 compute 的桶锁.
        KeyState<K, T, P> state = this.store.get(key);
        if (state != null) {
            P existing = state.partition;
            if (existing != null) {
                this.afterPartitionAccess(existing);
                return existing;
            }
        }
        // KeyState 可能不存在, 也可能只剩句柄(先 at 后读, 或分区已删), 统一在 compute 内补建分区.
        AtomicReference<P> resolved = new AtomicReference<>();
        this.store.compute(key, (k, existing) -> {
            KeyState<K, T, P> target = existing != null ? existing : new KeyState<>();
            P current = target.partition;
            if (current == null) {
                P created = this.createPartition(k);
                // 接到该 key 已有的 PartitionHandle 上, 挂载完成后才发布 partition 字段.
                PartitionHandle<K, T> handle = this.liveHandle(target);
                if (handle != null) {
                    handle.attach(created);
                }
                target.partition = created;
                current = created;
            }
            resolved.set(current);
            return target;
        });
        P partition = resolved.get();
        this.afterPartitionAccess(partition);
        return partition;
    }

    /**
     * 分区被取用后的回调, 在取用返回之前执行.
     * <p>本回调对<strong>每次</strong>取用分区都会调用, 因此实现必须幂等.
     */
    void afterPartitionAccess(P partition) {
    }

    @Override
    public T get(@NotNull K key) {
        return this.partition(key).get();
    }

    /**
     * 返回的是持有分区本身的 {@link PartitionHandle}, 跨越删除重建后持续有效.
     * 缓存是弱的, 句柄只在调用方或某条绑定还持有它时存活, 因此只读取过而没有绑定过的 key 不留痕迹.
     * 删除释放的是分区及其缓存值.
     */
    @Override
    @NotNull
    public Signal<T> at(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        this.purgeDeadHandles();
        // 句柄与分区都在且转发已挂好时直接返回. 这条快路径只读不挂载, 挂载与换挂仍收在该 key 的 compute 内.
        KeyState<K, T, P> state = this.store.get(key);
        if (state != null) {
            PartitionHandle<K, T> live = this.liveHandle(state);
            P current = state.partition;
            if (live != null && current != null && live.isAttachedTo(current)) {
                this.afterPartitionAccess(current);
                return live;
            }
        }
        // 句柄可能晚于分区出现(先 get 后 at), 也可能反过来; 补建与挂载在一次 compute 内原子完成.
        AtomicReference<PartitionHandle<K, T>> resolvedHandle = new AtomicReference<>();
        AtomicReference<P> resolvedPartition = new AtomicReference<>();
        this.store.compute(key, (k, existing) -> {
            KeyState<K, T, P> target = existing != null ? existing : new KeyState<>();
            PartitionHandle<K, T> handle = this.liveHandle(target);
            if (handle == null) {
                handle = new PartitionHandle<>(this, k);
                // 强引用经 resolvedHandle 逃出 compute, 否则刚建好的句柄可能在返回给调用方之前就被回收.
                target.handleRef = new HandleReference<>(handle, k, this.deadHandles);
            }
            P current = target.partition;
            if (current == null) {
                P created = this.createPartition(k);
                handle.attach(created);
                target.partition = created;
                current = created;
            } else {
                handle.attach(current);
            }
            resolvedHandle.set(handle);
            resolvedPartition.set(current);
            return target;
        });
        this.afterPartitionAccess(resolvedPartition.get());
        return resolvedHandle.get();
    }

    // 返回 KeyState 上仍存活的句柄, 没有则返回 null.
    @Nullable
    private PartitionHandle<K, T> liveHandle(KeyState<K, T, P> state) {
        HandleReference<K, T> reference = state.handleRef;
        return reference == null ? null : reference.get();
    }

    // 清除句柄已被回收的 KeyState 残留.
    private void purgeDeadHandles() {
        Reference<? extends PartitionHandle<K, T>> reference;
        while ((reference = this.deadHandles.poll()) != null) {
            if (!(reference instanceof HandleReference<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            HandleReference<K, T> dead = (HandleReference<K, T>) reference;
            this.store.computeIfPresent(dead.key, (ignored, state) -> {
                // 只在 KeyState 仍指向这条死引用时清除, 免得误清同 key 新建的句柄.
                if (state.handleRef != dead) {
                    return state;
                }
                state.handleRef = null;
                return state.partition == null ? null : state;
            });
        }
    }

    @Override
    public void dirty(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        KeyState<K, T, P> state = this.store.get(key);
        if (state != null) {
            P partition = state.partition;
            if (partition != null) {
                this.dirtyPartition(partition);
            }
        }
    }

    @Override
    public void dirtyAll() {
        this.store.forEachValue(state -> {
            P partition = state.partition;
            if (partition != null) {
                this.dirtyPartition(partition);
            }
        });
    }

    @Override
    public void remove(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        this.purgeDeadHandles();
        this.store.computeIfPresent(key, (ignored, state) -> {
            P partition = state.partition;
            if (partition != null) {
                // 分区终止会关闭它的全部订阅.
                partition.retire();
                PartitionHandle<K, T> handle = this.liveHandle(state);
                if (handle != null) {
                    handle.onPartitionEvicted(partition);
                }
                state.partition = null;
            }
            // 句柄也不在了的 KeyState 没有存在意义.
            return this.liveHandle(state) == null ? null : state;
        });
    }

    // 当前已激活的分区数.
    final int partitionCount() {
        int[] count = new int[1];
        this.store.forEachValue(state -> {
            if (state.partition != null) {
                count[0]++;
            }
        });
        return count[0];
    }

    // 当前仍存活的 {@link PartitionHandle} 数.
    final int handleCount() {
        this.purgeDeadHandles();
        int[] count = new int[1];
        this.store.forEachValue(state -> {
            if (this.liveHandle(state) != null) {
                count[0]++;
            }
        });
        return count[0];
    }

    @Override
    public void clear() {
        this.store.forEachKey(this::remove);
    }

    /**
     * 一个 key 名下的全部状态, 持有分区与句柄弱引用.
     * <p>两个字段的写入操作都收在该 key 的 compute 内.
     */
    static final class KeyState<K, T, P extends AbstractSignal<T>> {
        volatile P partition;                       // 当前分区, null 表示未建或已删
        volatile HandleReference<K, T> handleRef;   // 句柄的弱引用, null 表示无人取过句柄
    }

    /**
     * 句柄的弱引用, 携带 key 以便句柄回收后清掉对应 KeyState 的残留.
     */
    private static final class HandleReference<K, T> extends WeakReference<PartitionHandle<K, T>> {
        private final K key;

        private HandleReference(PartitionHandle<K, T> handle, K key, ReferenceQueue<? super PartitionHandle<K, T>> queue) {
            super(handle, queue);
            this.key = key;
        }
    }
}
