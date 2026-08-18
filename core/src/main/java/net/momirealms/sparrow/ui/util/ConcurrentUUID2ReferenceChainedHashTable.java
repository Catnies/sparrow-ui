package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * UUID 到引用的并发哈希表, key 以两个 {@code long} 内联在节点里, 查找比较
 * 散列对完整 128 位做 fmix64 混合, 对顺序生成等结构性 UUID 仍保持均匀分布.
 * <p>读路径全程无锁, 写按桶串行, 锁是桶头节点自身.
 * 扩容按桶迁移, 迁移完的桶写入 {@code RESIZE_NODE} 指路, 读写与遍历跟着它转到新表.
 * <p>使用契约:
 * <ul>
 *   <li>值不允许为 {@code null}; compute 家族的函数返回 {@code null} 表示删除或不插入.</li>
 *   <li>同一 key 上的 compute 家族互斥, 函数内可以安全触碰外部状态, 但不得再操作本表.</li>
 *   <li>{@link #forEachKey} 与 {@link #forEachValue} 弱一致: 保证看到调用开始前已存在的映射,
 *       期间的并发修改可能可见也可能不可见.</li>
 * </ul>
 *
 * @param <V> 值类型
 */
public final class ConcurrentUUID2ReferenceChainedHashTable<V> {
    private static final int DEFAULT_CAPACITY = 16;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;
    private static final int MAXIMUM_CAPACITY = Integer.MIN_VALUE >>> 1;
    private static final int THRESHOLD_NO_RESIZE = -1;    // 阈值哨兵: 不允许扩容
    private static final int THRESHOLD_RESIZING = -2;     // 阈值哨兵: 正在扩容
    private static final TableEntry<?> RESIZE_NODE = new TableEntry<>(0L, 0L, null); // 桶头哨兵: 该桶已迁移到新表
    private static final VarHandle THRESHOLD_HANDLE;

    static {
        try {
            THRESHOLD_HANDLE = MethodHandles.lookup().findVarHandle(ConcurrentUUID2ReferenceChainedHashTable.class, "threshold", int.class);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    // size 为映射数量, loadFactor 决定扩容阈值; table 是当前桶数组, nextTable 是扩容目标表,
    // threshold 保存扩容阈值或上面的哨兵值, 通过 VarHandle 做内存语义访问
    private final AtomicLong size = new AtomicLong();
    private final float loadFactor;
    private volatile TableEntry<V>[] table;
    private volatile TableEntry<V>[] nextTable;
    private volatile int threshold;

    /**
     * 以默认容量(16)和默认负载因子(0.75)创建空表.
     */
    public ConcurrentUUID2ReferenceChainedHashTable() {
        this(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR);
    }

    private ConcurrentUUID2ReferenceChainedHashTable(int capacity, float loadFactor) {
        if (loadFactor <= 0.0f || !Float.isFinite(loadFactor)) {
            throw new IllegalArgumentException("Invalid load factor: " + loadFactor);
        }
        int tableSize = capacityFor(capacity);
        if (tableSize == MAXIMUM_CAPACITY) {
            this.setThresholdPlain(THRESHOLD_NO_RESIZE);
        } else {
            this.setThresholdPlain(targetThreshold(tableSize, loadFactor));
        }
        this.loadFactor = loadFactor;
        this.table = createTable(tableSize);
        this.nextTable = this.table;
    }

    @NotNull
    public static <V> ConcurrentUUID2ReferenceChainedHashTable<V> createWithExpected(int expected) {
        double capacity = Math.ceil((double) expected / (double) DEFAULT_LOAD_FACTOR);
        return createWithCapacity((int) Math.min(capacity, (double) Integer.MAX_VALUE), DEFAULT_LOAD_FACTOR);
    }

    @NotNull
    public static <V> ConcurrentUUID2ReferenceChainedHashTable<V> createWithCapacity(int capacity, float loadFactor) {
        return new ConcurrentUUID2ReferenceChainedHashTable<>(capacity, loadFactor);
    }

    @Nullable
    public V get(@NotNull UUID key) {
        Objects.requireNonNull(key, "key");
        TableEntry<V> node = this.getNode(key.getMostSignificantBits(), key.getLeastSignificantBits());
        // 占位节点的 value 为 null, 天然视作未映射.
        return node == null ? null : node.getValueVolatile();
    }

    @Nullable
    private TableEntry<V> getNode(long msb, long lsb) {
        int hash = hash(msb, lsb);
        TableEntry<V>[] table = this.table;
        for (;;) {
            TableEntry<V> node = getAtIndexAcquire(table, hash & (table.length - 1));
            if (node == null) {
                return null;
            }
            if (node == RESIZE_NODE) {
                table = this.fetchNewTable(table);
                continue;
            }
            do {
                if (node.keyMsb == msb && node.keyLsb == lsb) {
                    return node;
                }
                node = node.getNextAcquire();
            } while (node != null);
            return null;
        }
    }

    @Nullable
    public V computeIfAbsent(@NotNull UUID key, @NotNull Function<? super UUID, ? extends V> function) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(function, "function");
        long msb = key.getMostSignificantBits();
        long lsb = key.getLeastSignificantBits();

        TableEntry<V> present = this.getNode(msb, lsb);
        if (present != null) {
            V existing = present.getValueVolatile();
            if (existing != null) {
                return existing;
            }
            // 撞上占位节点, 落到加锁路径与占位者汇合.
        }

        int hash = hash(msb, lsb);
        TableEntry<V>[] table = this.table;
        table_loop:
        for (;;) {
            int index = hash & (table.length - 1);
            TableEntry<V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                V ret = null;
                if (node == null) {
                    // 空桶用占位节点抢位, 函数在占位锁内执行, 其他写者会阻塞到占位完成或还原.
                    TableEntry<V> insert = new TableEntry<>(msb, lsb, null);
                    boolean added = false;
                    synchronized (insert) {
                        if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, insert))) {
                            try {
                                ret = function.apply(key);
                            } catch (Throwable throwable) {
                                setAtIndexVolatile(table, index, null);
                                throw sneakyThrow(throwable);
                            }
                            if (ret == null) {
                                setAtIndexVolatile(table, index, null);
                                return null;
                            }
                            insert.setValueRelease(ret);
                            added = true;
                        } // else: 抢位失败, 带着读到的桶头掉到下方分支.
                    }
                    if (added) {
                        this.addSize(1L);
                        return ret;
                    }
                }
                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }
                boolean added = false;
                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // 桶锁内只有本线程改写这条链, plain 读安全.
                    TableEntry<V> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if (node.keyMsb == msb && node.keyLsb == lsb) {
                            return node.getValuePlain();
                        }
                    }
                    V computed = function.apply(key);
                    if (computed != null) {
                        // release 保证读者顺着链看到完整初始化的新节点.
                        prev.setNextRelease(new TableEntry<>(msb, lsb, computed));
                        ret = computed;
                        added = true;
                    }
                }
                if (added) {
                    this.addSize(1L);
                }
                return ret;
            }
        }
    }

    @Nullable
    public V compute(@NotNull UUID key, @NotNull BiFunction<? super UUID, ? super V, ? extends V> function) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(function, "function");
        long msb = key.getMostSignificantBits();
        long lsb = key.getLeastSignificantBits();
        int hash = hash(msb, lsb);

        TableEntry<V>[] table = this.table;
        table_loop:
        for (;;) {
            int index = hash & (table.length - 1);
            TableEntry<V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                V ret = null;
                if (node == null) {
                    TableEntry<V> insert = new TableEntry<>(msb, lsb, null);
                    boolean added = false;
                    synchronized (insert) {
                        if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, insert))) {
                            try {
                                ret = function.apply(key, null);
                            } catch (Throwable throwable) {
                                setAtIndexVolatile(table, index, null);
                                throw sneakyThrow(throwable);
                            }
                            if (ret == null) {
                                setAtIndexVolatile(table, index, null);
                                return null;
                            }
                            insert.setValueRelease(ret);
                            added = true;
                        } // else: 抢位失败, 带着读到的桶头掉到下方分支.
                    }
                    if (added) {
                        this.addSize(1L);
                        return ret;
                    }
                }
                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }
                boolean removed = false;
                boolean added = false;
                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // 桶锁内只有本线程改写这条链, plain 读安全.
                    TableEntry<V> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if (node.keyMsb == msb && node.keyLsb == lsb) {
                            V computed = function.apply(key, node.getValuePlain());
                            if (computed != null) {
                                node.setValueVolatile(computed);
                                return computed;
                            }
                            // release 摘链, 保证读者看到完整的后继.
                            if (prev == null) {
                                setAtIndexRelease(table, index, node.getNextPlain());
                            } else {
                                prev.setNextRelease(node.getNextPlain());
                            }
                            removed = true;
                            break;
                        }
                    }
                    if (!removed) {
                        V computed = function.apply(key, null);
                        if (computed != null) {
                            prev.setNextRelease(new TableEntry<>(msb, lsb, computed));
                            ret = computed;
                            added = true;
                        }
                    }
                }
                if (removed) {
                    this.subSize(1L);
                }
                if (added) {
                    this.addSize(1L);
                }
                return ret;
            }
        }
    }

    @Nullable
    public V computeIfPresent(@NotNull UUID key, @NotNull BiFunction<? super UUID, ? super V, ? extends V> function) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(function, "function");
        long msb = key.getMostSignificantBits();
        long lsb = key.getLeastSignificantBits();
        int hash = hash(msb, lsb);

        TableEntry<V>[] table = this.table;
        table_loop:
        for (;;) {
            int index = hash & (table.length - 1);
            TableEntry<V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return null;
                }
                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }
                boolean removed = false;
                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // 桶锁内只有本线程改写这条链, plain 读安全.
                    TableEntry<V> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if (node.keyMsb == msb && node.keyLsb == lsb) {
                            V computed = function.apply(key, node.getValuePlain());
                            if (computed != null) {
                                node.setValueVolatile(computed);
                                return computed;
                            }
                            if (prev == null) {
                                setAtIndexRelease(table, index, node.getNextPlain());
                            } else {
                                prev.setNextRelease(node.getNextPlain());
                            }
                            removed = true;
                            break;
                        }
                    }
                }
                if (removed) {
                    this.subSize(1L);
                }
                return null;
            }
        }
    }

    @Nullable
    public V remove(@NotNull UUID key) {
        Objects.requireNonNull(key, "key");
        return this.remove(key.getMostSignificantBits(), key.getLeastSignificantBits());
    }

    @Nullable
    private V remove(long msb, long lsb) {
        int hash = hash(msb, lsb);
        TableEntry<V>[] table = this.table;
        table_loop:
        for (;;) {
            int index = hash & (table.length - 1);
            TableEntry<V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return null;
                }
                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }
                boolean removed = false;
                V ret = null;
                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // 桶锁内只有本线程改写这条链, plain 读安全.
                    TableEntry<V> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if (node.keyMsb == msb && node.keyLsb == lsb) {
                            ret = node.getValuePlain();
                            removed = true;
                            if (prev == null) {
                                setAtIndexRelease(table, index, node.getNextPlain());
                            } else {
                                prev.setNextRelease(node.getNextPlain());
                            }
                            break;
                        }
                    }
                }
                if (removed) {
                    this.subSize(1L);
                }
                return ret;
            }
        }
    }

    public void clear() {
        NodeIterator<V> iterator = new NodeIterator<>(this);
        TableEntry<V> node;
        while ((node = iterator.findNext()) != null) {
            this.remove(node.keyMsb, node.keyLsb);
        }
    }

    public int size() {
        return (int) Math.clamp(this.size.get(), 0L, (long) Integer.MAX_VALUE);
    }

    // 当前桶数组长度, 供白盒测试观察扩容结果.
    int capacity() {
        return this.table.length;
    }

    public void forEachKey(@NotNull Consumer<? super UUID> action) {
        Objects.requireNonNull(action, "action");
        NodeIterator<V> iterator = new NodeIterator<>(this);
        TableEntry<V> node;
        while ((node = iterator.findNext()) != null) {
            action.accept(new UUID(node.keyMsb, node.keyLsb));
        }
    }

    public void forEachValue(@NotNull Consumer<? super V> action) {
        Objects.requireNonNull(action, "action");
        NodeIterator<V> iterator = new NodeIterator<>(this);
        TableEntry<V> node;
        while ((node = iterator.findNext()) != null) {
            V value = node.getValueVolatile();
            if (value != null) {
                action.accept(value);
            }
        }
    }

    // 对完整 128 位做 murmur3 fmix64 混合, 让顺序生成的结构性 UUID 也均匀落桶.
    private static int hash(long msb, long lsb) {
        long hash = msb ^ Long.rotateLeft(lsb, 32);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (int) hash;
    }

    // 返回不小于 capacity 的最小 2 的幂, 顶到 MAXIMUM_CAPACITY 为止.
    private static int capacityFor(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Invalid capacity: " + capacity);
        }
        if (capacity >= MAXIMUM_CAPACITY) {
            return MAXIMUM_CAPACITY;
        }
        return 1 << (32 - Integer.numberOfLeadingZeros(capacity - 1));
    }

    private static int floorLog2(int value) {
        return 31 - Integer.numberOfLeadingZeros(value);
    }

    private static int targetThreshold(int capacity, float loadFactor) {
        double target = (double) capacity * (double) loadFactor;
        if (Double.isInfinite(target) || target >= ((double) Integer.MAX_VALUE - 1)) {
            return THRESHOLD_NO_RESIZE;
        }
        return (int) Math.ceil(target);
    }

    /**
     * 在 expectedCurr 表里撞见 {@code RESIZE_NODE} 后解析应当前往的表.
     * <p>迁移逻辑保证 RESIZE_NODE 只在整条链搬完后才写入原桶, 因此拿到下一张表就能看到完整的链.
     * 但 nextTable 可能已被再下一轮扩容覆盖, 那时当前表已不是 expectedCurr, 直接改用当前表.
     * <p>两个字段的读取顺序不可交换.
     */
    private TableEntry<V>[] fetchNewTable(TableEntry<V>[] expectedCurr) {
        TableEntry<V>[] candidate = this.nextTable;
        TableEntry<V>[] current = this.table;
        return expectedCurr == current ? candidate : current;
    }

    // 计数增加后越过阈值的线程负责扩容, 阈值被原子换成 RESIZING 挡住其他线程.
    private void addSize(long count) {
        long sum = this.size.addAndGet(count);
        int threshold = this.getThresholdAcquire();
        if (threshold < 0) {
            // 正在扩容或不允许扩容.
            return;
        }
        if (sum < (long) threshold) {
            return;
        }
        if (threshold != this.compareExchangeThresholdVolatile(threshold, THRESHOLD_RESIZING)) {
            // 别的线程抢到了扩容权.
            return;
        }
        this.resize(sum);
    }

    private void subSize(long count) {
        this.size.getAndAdd(-count);
    }

    /**
     * 迁移到更大的表, 仅由把阈值换成 {@code THRESHOLD_RESIZING} 成功的那个线程调用.
     * <p>逐桶持锁迁移: 单节点链直接移动原节点, 多节点链复制成新节点, 读者继续读旧链的快照;
     * 搬完的桶以 release 写入 {@code RESIZE_NODE}, 之后到来的读写循着它转到新表.
     * <p>迁移期间别的线程只累加计数就返回, 因此一轮搬完后要复查是否已经再次越界,
     * 越界就再扩一轮; 每轮容量至少翻倍, 循环必然收敛.
     *
     * @param sum 当前映射数的估计值, 不小于旧阈值
     */
    private void resize(long sum) {
        for (;;) {
            int capacity;
            // 加 1.0 是因为 sum 可能恰好等于阈值(此时 sum / loadFactor 即当前容量),
            // 抬一手再向上取 2 的幂, 保证容量至少翻倍.
            double target = ((double) sum / (double) this.loadFactor) + 1.0;
            if (target >= (double) MAXIMUM_CAPACITY) {
                capacity = MAXIMUM_CAPACITY;
            } else {
                capacity = Math.min(capacityFor((int) Math.ceil(target)), MAXIMUM_CAPACITY);
            }

            TableEntry<V>[] newTable = createTable(capacity);
            TableEntry<V>[] oldTable = this.table;
            this.nextTable = newTable;

            // 旧桶 i 的节点只会落到新表的 capacity/oldTable.length 个候选桶里,
            // work 数组按候选桶数记录各自链尾, 一趟遍历就把整条链分发完.
            int capOldShift = floorLog2(oldTable.length);
            int capDiffShift = floorLog2(capacity) - capOldShift;
            if (capDiffShift == 0) {
                throw new IllegalStateException("Resizing to same size");
            }
            TableEntry<V>[] work = createTable(1 << capDiffShift);

            for (int i = 0, len = oldTable.length; i < len; ++i) {
                TableEntry<V> binNode = getAtIndexAcquire(oldTable, i);
                for (;;) {
                    if (binNode == null) {
                        // 空桶直接抢占为 RESIZE_NODE, 无需搬运.
                        if (null == (binNode = compareAndExchangeAtIndexVolatile(oldTable, i, null, resizeNode()))) {
                            break;
                        } // else: 抢占失败, 桶头已有节点, 掉到持锁迁移.
                    }
                    synchronized (binNode) {
                        if (binNode != (binNode = getAtIndexAcquire(oldTable, i))) {
                            continue;
                        }
                        // 持有桶锁后写新表无需同步: RESIZE_NODE 尚未写入, 没有读写者会访问新表对应的桶.
                        TableEntry<V> next = binNode.getNextPlain();
                        if (next == null) {
                            // 单节点链直接移动原节点, 写者会经由旧桶的 RESIZE_NODE 自动转到它.
                            newTable[hash(binNode.keyMsb, binNode.keyLsb) & (capacity - 1)] = binNode;
                        } else {
                            Arrays.fill(work, null);
                            for (TableEntry<V> curr = binNode; curr != null; curr = curr.getNextPlain()) {
                                int newTableIdx = hash(curr.keyMsb, curr.keyLsb) & (capacity - 1);
                                int workIdx = newTableIdx >>> capOldShift;
                                // 复制而不是移动: 读者只保证看到调用开始时的状态, 旧链保持原样即可.
                                TableEntry<V> replace = new TableEntry<>(curr.keyMsb, curr.keyLsb, curr.getValuePlain());
                                TableEntry<V> workNode = work[workIdx];
                                work[workIdx] = replace;
                                if (workNode == null) {
                                    newTable[newTableIdx] = replace;
                                } else {
                                    workNode.setNextPlain(replace);
                                }
                            }
                        }
                        setAtIndexRelease(oldTable, i, resizeNode());
                        break;
                    }
                }
            }

            int newThreshold;
            if (capacity == MAXIMUM_CAPACITY) {
                newThreshold = THRESHOLD_NO_RESIZE;
            } else {
                newThreshold = targetThreshold(capacity, this.loadFactor);
            }
            this.table = newTable;
            // 先写回正常阈值再复查计数, 顺序不可交换: 反过来会留下一个窗口,
            // 窗口内的插入既看不到正常阈值也不在本轮复查内, 越界就此无人接手.
            this.setThresholdVolatile(newThreshold);
            if (newThreshold == THRESHOLD_NO_RESIZE) {
                return;
            }
            sum = this.size.get();
            if (sum < (long) newThreshold) {
                return;
            }
            // 迁移期间攒下的增量已经越界, 重新取得扩容权再来一轮;
            // 取不到说明别的线程刚抢走, 由它接手.
            if (newThreshold != this.compareExchangeThresholdVolatile(newThreshold, THRESHOLD_RESIZING)) {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> TableEntry<V>[] createTable(int capacity) {
        return (TableEntry<V>[]) new TableEntry[capacity];
    }

    @SuppressWarnings("unchecked")
    private static <V> TableEntry<V> resizeNode() {
        return (TableEntry<V>) RESIZE_NODE;
    }

    @SuppressWarnings("unchecked")
    private static <V> TableEntry<V> getAtIndexAcquire(TableEntry<V>[] table, int index) {
        return (TableEntry<V>) TableEntry.TABLE_ENTRY_ARRAY_HANDLE.getAcquire(table, index);
    }

    private static <V> void setAtIndexRelease(TableEntry<V>[] table, int index, TableEntry<V> value) {
        TableEntry.TABLE_ENTRY_ARRAY_HANDLE.setRelease(table, index, value);
    }

    private static <V> void setAtIndexVolatile(TableEntry<V>[] table, int index, TableEntry<V> value) {
        TableEntry.TABLE_ENTRY_ARRAY_HANDLE.setVolatile(table, index, value);
    }

    @SuppressWarnings("unchecked")
    private static <V> TableEntry<V> compareAndExchangeAtIndexVolatile(TableEntry<V>[] table, int index, TableEntry<V> expect, TableEntry<V> update) {
        return (TableEntry<V>) TableEntry.TABLE_ENTRY_ARRAY_HANDLE.compareAndExchange(table, index, expect, update);
    }

    private int getThresholdAcquire() {
        return (int) THRESHOLD_HANDLE.getAcquire(this);
    }

    private void setThresholdPlain(int threshold) {
        THRESHOLD_HANDLE.set(this, threshold);
    }

    private void setThresholdVolatile(int threshold) {
        THRESHOLD_HANDLE.setVolatile(this, threshold);
    }

    private int compareExchangeThresholdVolatile(int expect, int update) {
        return (int) THRESHOLD_HANDLE.compareAndExchange(this, expect, update);
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws X {
        throw (X) throwable;
    }

    /**
     * 弱一致的节点游标: 保证给出调用开始前已存在的节点, 撞见 {@code RESIZE_NODE} 时沿扩容链
     * 下潜到新表继续, 读完新表中源自当前桶的部分再回到旧表的下一个桶.
     */
    private static final class NodeIterator<V> {
        private final ConcurrentUUID2ReferenceChainedHashTable<V> map;
        private TableEntry<V>[] currentTable;
        private ResizeChain<V> resizeChain;
        private TableEntry<V> last;
        private int nextBin;
        private int increment;

        private NodeIterator(ConcurrentUUID2ReferenceChainedHashTable<V> map) {
            this.map = map;
            this.currentTable = map.table;
            this.increment = 1;
        }

        @Nullable
        private TableEntry<V> findNext() {
            for (;;) {
                TableEntry<V> last = this.last;
                if (last != null) {
                    TableEntry<V> next = last.getNextVolatile();
                    if (next != null) {
                        this.last = next;
                        if (next.getValuePlain() == null) {
                            // compute 家族的占位节点还没就绪, 跳过.
                            continue;
                        }
                        return next;
                    }
                }

                TableEntry<V>[] table = this.currentTable;
                if (table == null) {
                    return null;
                }

                int idx = this.nextBin;
                int increment = this.increment;
                for (;;) {
                    if (idx >= table.length) {
                        table = this.pullResizeChain(idx);
                        idx = this.nextBin;
                        increment = this.increment;
                        if (table != null) {
                            continue;
                        }
                        this.last = null;
                        return null;
                    }

                    TableEntry<V> entry = getAtIndexAcquire(table, idx);
                    if (entry == null) {
                        idx += increment;
                        continue;
                    }
                    if (entry == RESIZE_NODE) {
                        table = this.pushResizeChain(table);
                        increment = this.increment;
                        continue;
                    }

                    this.last = entry;
                    this.nextBin = idx + increment;
                    if (entry.getValuePlain() == null) {
                        // compute 家族的占位节点还没就绪, 从它的后继接着找.
                        break;
                    }
                    return entry;
                }
            }
        }

        // 撞见 RESIZE_NODE, 顺着扩容链下潜到新表, 步长换成当前表长以便只扫源自原桶的桶位.
        private TableEntry<V>[] pushResizeChain(TableEntry<V>[] table) {
            ResizeChain<V> chain = this.resizeChain;
            if (chain == null) {
                TableEntry<V>[] nextTable = this.map.fetchNewTable(table);
                ResizeChain<V> oldChain = new ResizeChain<>(table, null, null);
                ResizeChain<V> currChain = new ResizeChain<>(nextTable, oldChain, null);
                oldChain.next = currChain;
                this.increment = table.length;
                this.resizeChain = currChain;
                this.currentTable = nextTable;
                return nextTable;
            }
            ResizeChain<V> currChain = chain.next;
            if (currChain == null) {
                TableEntry<V>[] ret = this.map.fetchNewTable(table);
                currChain = new ResizeChain<>(ret, chain, null);
                chain.next = currChain;
                this.increment = table.length;
                this.resizeChain = currChain;
                this.currentTable = ret;
                return ret;
            }
            this.increment = table.length;
            this.resizeChain = currChain;
            return this.currentTable = currChain.table;
        }

        // 新表中源自原桶的桶位扫完了, 浮回上一张表的下一个桶.
        @Nullable
        private TableEntry<V>[] pullResizeChain(int index) {
            ResizeChain<V> resizeChain = this.resizeChain;
            if (resizeChain == null) {
                this.currentTable = null;
                return null;
            }
            ResizeChain<V> prevChain = resizeChain.prev;
            this.resizeChain = prevChain;
            if (prevChain == null) {
                this.currentTable = null;
                return null;
            }
            TableEntry<V>[] newTable = prevChain.table;

            // 下潜期间加的步长都是新表长的倍数, 对新表长取模即可还原原桶位.
            int newIdx = index & (newTable.length - 1);
            ResizeChain<V> nextPrevChain = prevChain.prev;
            int increment;
            if (nextPrevChain == null) {
                increment = 1;
            } else {
                increment = nextPrevChain.table.length;
            }
            // 该桶已处理完, 跨到下一个.
            newIdx += increment;

            this.increment = increment;
            this.nextBin = newIdx;
            this.currentTable = newTable;
            return newTable;
        }

        private static final class ResizeChain<V> {
            private final TableEntry<V>[] table;
            private final ResizeChain<V> prev;
            private ResizeChain<V> next;

            private ResizeChain(TableEntry<V>[] table, ResizeChain<V> prev, ResizeChain<V> next) {
                this.table = table;
                this.prev = prev;
                this.next = next;
            }
        }
    }

    /**
     * 链节点. key 内联为两个 {@code long}, 比较不经过 {@link UUID} 对象;
     * value 为 {@code null} 时是 compute 家族进行中的占位, 不视作已映射.
     */
    private static final class TableEntry<V> {
        private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(TableEntry[].class);
        private static final VarHandle VALUE_HANDLE;
        private static final VarHandle NEXT_HANDLE;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                VALUE_HANDLE = lookup.findVarHandle(TableEntry.class, "value", Object.class);
                NEXT_HANDLE = lookup.findVarHandle(TableEntry.class, "next", TableEntry.class);
            } catch (NoSuchFieldException | IllegalAccessException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        private final long keyMsb;
        private final long keyLsb;
        private volatile V value;
        private volatile TableEntry<V> next;

        private TableEntry(long keyMsb, long keyLsb, V value) {
            this.keyMsb = keyMsb;
            this.keyLsb = keyLsb;
            this.setValuePlain(value);
        }

        @SuppressWarnings("unchecked")
        private V getValuePlain() {
            return (V) VALUE_HANDLE.get(this);
        }

        private void setValuePlain(V value) {
            VALUE_HANDLE.set(this, (Object) value);
        }

        @SuppressWarnings("unchecked")
        private V getValueVolatile() {
            return (V) VALUE_HANDLE.getVolatile(this);
        }

        private void setValueVolatile(V value) {
            VALUE_HANDLE.setVolatile(this, (Object) value);
        }

        private void setValueRelease(V value) {
            VALUE_HANDLE.setRelease(this, (Object) value);
        }

        @SuppressWarnings("unchecked")
        private TableEntry<V> getNextPlain() {
            return (TableEntry<V>) NEXT_HANDLE.get(this);
        }

        private void setNextPlain(TableEntry<V> next) {
            NEXT_HANDLE.set(this, next);
        }

        @SuppressWarnings("unchecked")
        private TableEntry<V> getNextAcquire() {
            return (TableEntry<V>) NEXT_HANDLE.getAcquire(this);
        }

        @SuppressWarnings("unchecked")
        private TableEntry<V> getNextVolatile() {
            return (TableEntry<V>) NEXT_HANDLE.getVolatile(this);
        }

        private void setNextRelease(TableEntry<V> next) {
            NEXT_HANDLE.setRelease(this, next);
        }
    }
}
