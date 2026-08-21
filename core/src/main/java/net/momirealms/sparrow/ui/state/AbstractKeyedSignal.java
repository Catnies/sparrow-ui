package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KeyedSignal 抽象实现骨架.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 * @param <P> 分区实现类型
 */
abstract class AbstractKeyedSignal<K, T, P extends AbstractSignal<T>> implements KeyedSignal<K, T> {
private final KeyStateStore<K, KeyState<K, T, P>> store;                                                 // key -> (分区 & 句柄), 只放有分区的 key.
    private final WeakHashMap<K, WeakReference<PartitionHandle<K, T>>> detached = new WeakHashMap<>();   // 分区已删但仍有人持有的句柄, key 用句柄自己那份
    private final Object detachedLock = new Object();                                                    // 保护 detached, 锁序固定为主表 compute 在外

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
        // 读值路径上绝大多数取用命中已有分区, 这条快路径避开 compute 的桶锁.
        KeyState<K, T, P> state = this.store.get(key);
        if (state != null) {
            this.afterPartitionAccess(state.partition);
            return state.partition;
        }
        // 没有分区就在 compute 内补建, 该 key 若有寄放在旁表里的句柄一并接回来.
        AtomicReference<P> resolved = new AtomicReference<>();
        this.store.compute(key, (k, existing) -> {
            if (existing != null) {
                resolved.set(existing.partition);
                return existing;
            }
            P created = this.createPartition(k);
            KeyState<K, T, P> target = new KeyState<>(created);
            PartitionHandle<K, T> handle = this.takeDetached(k);
            if (handle != null) {
                // 挂载完成后才随 KeyState 一起发布.
                handle.attach(created);
                target.handleRef = new WeakReference<>(handle);
            }
            resolved.set(created);
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
        // 句柄与分区都在且转发已挂好时直接返回. 这条快路径只读不挂载, 挂载与换挂仍收在该 key 的 compute 内.
        KeyState<K, T, P> state = this.store.get(key);
        if (state != null) {
            PartitionHandle<K, T> live = this.liveHandle(state);
            if (live != null && live.isAttachedTo(state.partition)) {
                this.afterPartitionAccess(state.partition);
                return live;
            }
        }
        // 句柄可能晚于分区出现(先 get 后 at), 也可能反过来; 补建与挂载在一次 compute 内原子完成.
        AtomicReference<PartitionHandle<K, T>> resolvedHandle = new AtomicReference<>();
        AtomicReference<P> resolvedPartition = new AtomicReference<>();
        this.store.compute(key, (k, existing) -> {
            KeyState<K, T, P> target = existing != null ? existing : new KeyState<>(this.createPartition(k));
            PartitionHandle<K, T> handle = this.liveHandle(target);
            if (handle == null) {
                // 分区删过还没重建时句柄寄放在旁表里, 先接回来, 没有才新建.
                handle = this.takeDetached(k);
                if (handle == null) {
                    handle = new PartitionHandle<>(this, k);
                }
                // 强引用经 resolvedHandle 逃出 compute, 否则刚建好的句柄可能在返回给调用方之前就被回收.
                target.handleRef = new WeakReference<>(handle);
            }
            handle.attach(target.partition);
            resolvedHandle.set(handle);
            resolvedPartition.set(target.partition);
            return target;
        });
        this.afterPartitionAccess(resolvedPartition.get());
        return resolvedHandle.get();
    }

    // 返回 KeyState 上仍存活的句柄, 没有则返回 null.
    @Nullable
    private PartitionHandle<K, T> liveHandle(KeyState<K, T, P> state) {
        WeakReference<PartitionHandle<K, T>> reference = state.handleRef;
        return reference == null ? null : reference.get();
    }

    // 把分区已删但仍存活的句柄寄放到旁表. 只在该 key 的 compute 内调用.
    private void parkDetached(PartitionHandle<K, T> handle) {
        synchronized (this.detachedLock) {
            // 旁表的 key 用句柄自己那份, key 就只被句柄强持. 先移掉同 key 的旧条目: WeakHashMap 的 put 不换 key 对象,
            // 留着旧 key 对象会让这条新寄放跟着旧 key 的回收一起蒸发.
            this.detached.remove(handle.key());
            this.detached.put(handle.key(), new WeakReference<>(handle));
        }
    }

    // 取回旁表里该 key 仍存活的句柄, 没有则返回 null; 条目无论死活都一并摘掉. 只在该 key 的 compute 内调用.
    @Nullable
    private PartitionHandle<K, T> takeDetached(K key) {
        synchronized (this.detachedLock) {
            WeakReference<PartitionHandle<K, T>> reference = this.detached.remove(key);
            return reference == null ? null : reference.get();
        }
    }

    @Override
    public void dirty(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        KeyState<K, T, P> state = this.store.get(key);
        if (state != null) {
            this.dirtyPartition(state.partition);
        }
    }

    @Override
    public void dirtyAll() {
        this.store.forEachValue(state -> this.dirtyPartition(state.partition));
    }

    @Override
    public void remove(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        this.store.computeIfPresent(key, (ignored, state) -> {
            // 分区终止会关闭它的全部订阅.
            state.partition.retire();
            PartitionHandle<K, T> handle = this.liveHandle(state);
            if (handle != null) {
                handle.onPartitionEvicted(state.partition);
                this.parkDetached(handle);
            }
            // 主表只放有分区的 key, 条目整个删掉.
            return null;
        });
    }

    // 当前已激活的分区数.
    final int partitionCount() {
        int[] count = new int[1];
        this.store.forEachValue(ignored -> count[0]++);
        return count[0];
    }

    // 当前仍存活的 {@link PartitionHandle} 数, 主表与旁表一起算.
    final int handleCount() {
        int[] count = new int[1];
        this.store.forEachValue(state -> {
            if (this.liveHandle(state) != null) {
                count[0]++;
            }
        });
        synchronized (this.detachedLock) {
            for (WeakReference<PartitionHandle<K, T>> reference : this.detached.values()) {
                if (reference.get() != null) {
                    count[0]++;
                }
            }
        }
        return count[0];
    }

    @Override
    public void clear() {
        this.store.forEachKey(this::remove);
    }

    /**
     * 一个有分区的 key 的全部状态: 分区本身与句柄的弱引用.
     * <p>分区建好才进表, 句柄引用的写入收在该 key 的 compute 内.
     */
    static final class KeyState<K, T, P extends AbstractSignal<T>> {
        final P partition;                                          // 当前分区
        volatile WeakReference<PartitionHandle<K, T>> handleRef;    // 句柄的弱引用, null 表示无人取过句柄

        KeyState(P partition) {
            this.partition = partition;
        }
    }
}
