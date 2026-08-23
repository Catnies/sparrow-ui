package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KeyedSignal 抽象实现骨架.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 * @param <P> 分区实现类型
 */
abstract class AbstractKeyedSignal<K, T, P extends AbstractSignal<T>> implements KeyedSignal<K, T> {
    // key -> (分区 & 句柄), 只放有分区的 key. 遍历是弱一致的, clear() 因此可以边遍历边删.
    private final ConcurrentHashMap<K, KeyState<K, T, P>> store = new ConcurrentHashMap<>();
    private final WeakHashMap<K, WeakReference<PartitionHandle<K, T>>> detached = new WeakHashMap<>();   // 分区已删但仍有人持有的句柄, key 用句柄自己那份
    private final Object detachedLock = new Object();                                                    // 保护 detached 与 keys 的首次创建, 锁序固定为主表 compute 在外
    @Nullable private volatile Keys<K> keys;                                                             // 第一次 keys() 才建, 没建过时建行删行只多一次 volatile 读

    /**
     * 创建一个新分区实现.
     * 在该 key 的 compute 函数内被调用.
     */
    abstract P createPartition(K key);

    /**
     * 标脏一个已激活的分区.
     */
    abstract void dirtyPartition(P partition);

    // 建该 key 的句柄, 只在该 key 的 compute 内调用.
    PartitionHandle<K, T> createHandle(K key) {
        return new PartitionHandle<>(this, key);
    }

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
        boolean[] created = new boolean[1];
        this.store.compute(key, (k, existing) -> {
            if (existing != null) {
                resolved.set(existing.partition);
                return existing;
            }
            P fresh = this.createPartition(k);
            KeyState<K, T, P> target = new KeyState<>(fresh);
            PartitionHandle<K, T> handle = this.takeDetached(k);
            if (handle != null) {
                // 挂载完成后才随 KeyState 一起发布.
                handle.attach(fresh);
                target.handleRef = new WeakReference<>(handle);
            }
            resolved.set(fresh);
            created[0] = true;
            return target;
        });
        // 派发放在 compute 之外, 回调里再碰同一张表才不会撞上它的重入限制
        if (created[0]) this.keysChanged();
        P partition = resolved.get();
        this.afterPartitionAccess(partition);
        return partition;
    }

    /**
     * 分区被取用后的回调, 在取用返回之前执行.
     * <p>本回调对<strong>每次</strong>取用分区都会调用, 因此实现必须幂等.
     * <p>取用指的是读写这个分区的值, {@link #at} 只取句柄不算.
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
     * <p><strong>取句柄不算一次取用, 也不算一次订阅</strong>: 它会把分区建出来, 但不会推动装载, 转发也要等句柄有了订阅者才挂.
     * 异步来源的首载因此发生在第一次读, 而不是取句柄的这一刻.
     */
    @Override
    @NotNull
    public PartitionHandle<K, T> at(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        // 句柄与分区都在, 且句柄跟的正是这个分区时直接返回. 这条快路径只读, 让句柄跟上分区仍收在该 key 的 compute 内.
        KeyState<K, T, P> state = this.store.get(key);
        if (state != null) {
            PartitionHandle<K, T> live = this.liveHandle(state);
            if (live != null && live.isAttachedTo(state.partition)) {
                return live;
            }
        }
        // 句柄可能晚于分区出现(先 get 后 at), 也可能反过来; 补建与挂载在一次 compute 内原子完成.
        AtomicReference<PartitionHandle<K, T>> resolvedHandle = new AtomicReference<>();
        boolean[] created = new boolean[1];
        this.store.compute(key, (k, existing) -> {
            created[0] = existing == null;
            KeyState<K, T, P> target = existing != null ? existing : new KeyState<>(this.createPartition(k));
            PartitionHandle<K, T> handle = this.liveHandle(target);
            if (handle == null) {
                // 分区删过还没重建时句柄寄放在旁表里, 先接回来, 没有才新建.
                handle = this.takeDetached(k);
                if (handle == null) {
                    handle = this.createHandle(k);
                }
                // 强引用经 resolvedHandle 逃出 compute, 否则刚建好的句柄可能在返回给调用方之前就被回收.
                target.handleRef = new WeakReference<>(handle);
            }
            handle.attach(target.partition);
            resolvedHandle.set(handle);
            return target;
        });
        if (created[0]) this.keysChanged();
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
        for (KeyState<K, T, P> state : this.store.values()) {
            this.dirtyPartition(state.partition);
        }
    }

    @Override
    public void remove(@NotNull K key) {
        Objects.requireNonNull(key, "key");
        boolean[] removed = new boolean[1];
        this.store.computeIfPresent(key, (ignored, state) -> {
            // 分区终止会关闭它的全部订阅.
            state.partition.retire();
            PartitionHandle<K, T> handle = this.liveHandle(state);
            if (handle != null) {
                handle.onPartitionEvicted(state.partition);
                this.parkDetached(handle);
            }
            removed[0] = true;
            // 主表只放有分区的 key, 条目整个删掉.
            return null;
        });
        if (removed[0]) this.keysChanged();
    }

    @Override
    @NotNull
    public WeakKeyedControl<K> weakControl() {
        return new Control<>(this);
    }

    @Override
    @NotNull
    public Signal<Set<K>> keys() {
        Keys<K> current = this.keys;
        if (current != null) return current;
        synchronized (this.detachedLock) {
            if (this.keys == null) {
                this.keys = new Keys<>(this);
            }
            return this.keys;
        }
    }

    // 建行或删行之后调用, 从没人要过 keys() 时只是一次 volatile 读.
    private void keysChanged() {
        Keys<K> current = this.keys;
        if (current != null) current.changed();
    }

    // 当前已激活的分区数.
    final int partitionCount() {
        return this.store.size();
    }

    // 当前仍存活的 {@link PartitionHandle} 数, 主表与旁表一起算.
    final int handleCount() {
        int count = 0;
        for (KeyState<K, T, P> state : this.store.values()) {
            if (this.liveHandle(state) != null) {
                count++;
            }
        }
        synchronized (this.detachedLock) {
            for (WeakReference<PartitionHandle<K, T>> reference : this.detached.values()) {
                if (reference.get() != null) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public void clear() {
        for (K key : this.store.keySet()) {
            this.remove(key);
        }
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

    // 弱持目标的控制句柄, 目标回收之后每个方法都是空操作.
    static final class Control<K> implements WeakKeyedControl<K> {
        private final WeakReference<AbstractKeyedSignal<K, ?, ?>> target;

        private Control(AbstractKeyedSignal<K, ?, ?> target) {
            this.target = new WeakReference<>(target);
        }

        @Override
        public void dirty(@NotNull K key) {
            AbstractKeyedSignal<K, ?, ?> signal = this.target.get();
            if (signal != null) signal.dirty(key);
        }

        @Override
        public void dirtyAll() {
            AbstractKeyedSignal<K, ?, ?> signal = this.target.get();
            if (signal != null) signal.dirtyAll();
        }

        @Override
        public void remove(@NotNull K key) {
            AbstractKeyedSignal<K, ?, ?> signal = this.target.get();
            if (signal != null) signal.remove(key);
        }

        @Override
        public void clear() {
            AbstractKeyedSignal<K, ?, ?> signal = this.target.get();
            if (signal != null) signal.clear();
        }

        @Override
        public boolean isStale() {
            return this.target.get() == null;
        }
    }

    // 有分区的 key 的集合, 值是拉取时从主表复制的不可修改快照. 不持外部资源, 没有激活钩子.
    static final class Keys<K> extends AbstractSignal<Set<K>> {
        private final AbstractKeyedSignal<K, ?, ?> owner;
        private final AtomicLong version = new AtomicLong();

        private Keys(AbstractKeyedSignal<K, ?, ?> owner) {
            this.owner = owner;
        }

        @Override
        public Set<K> get() {
            return Set.copyOf(this.owner.store.keySet());
        }

        @Override
        long version() {
            return this.version.get();
        }

        private void changed() {
            this.version.incrementAndGet();
            this.notifyDirty();
        }
    }
}
