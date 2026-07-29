package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.TransactionPostEvent;
import net.momirealms.sparrow.ui.inventory.event.TransactionPreEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * 自己持有槽位数据的Inventory的并发骨架: 不可变快照 + 一把短写锁.
 * <p>所有槽位内容收敛为一个 volatile 引用指向的数组, 数组更新时之后走替换引用而不会修改.
 * 写操作由 {@link InventoryTransactions} 在写锁内完成校验和换引用, 用户回调一律安排在锁外执行.
 * <p>本类是所有事务的最终落地处(视图家族把逻辑槽换算到这里); 批量规划走
 * {@link InventoryPlanner}, 单槽操作在本类内直接实现. 子类只需提供堆叠上限与遍历顺序等配置点.
 * <p>Inventory实例必须经过安全发布(final 字段, volatile, 锁或线程启动边界)交给其他线程使用;
 * 通过普通字段随手传递(裸发布)不在内存模型的保障范围内.
 */
abstract class AbstractInventory extends SparrowInventory {
    private static final AtomicLong LOCK_ORDER_SOURCE = new AtomicLong();   // 锁序号发号器, 每创建一个 Inventory 发一个号

    private final long lockOrder = LOCK_ORDER_SOURCE.getAndIncrement(); // 跨 Inventory 事务按这个序号决定加锁先后
    private final ReentrantLock writeLock = new ReentrantLock();        // 只用来串行化写操作, 临界区内全是纯内存操作
    private final SlotOrder naturalOrder;                               // 遍历顺序的缺省回退, 构造时按槽位数建一次

    private final ObservableDispatcher<TransactionPreEvent> preUpdates = new ObservableDispatcher<>();   // pre  观察者的订阅登记处
    private final ObservableDispatcher<TransactionPostEvent> postUpdates = new ObservableDispatcher<>(); // post 观察者的订阅登记处
    private final ConcurrentLinkedQueue<TransactionPostEvent> pendingPostEvents = new ConcurrentLinkedQueue<>(); // 在锁内入队, 队列顺序即提交顺序
    private final AtomicBoolean drainingPostEvents = new AtomicBoolean(); // "正在派发"标志, 同一时刻至多一个线程负责派发

    private volatile @Nullable ItemStack @NotNull [] state; // 当前的不可变快照, 里面的物品归内部所有

    /**
     * 以给定数组为初始内容创建 Inventory.
     *
     * @param initial 初始槽位内容, 空槽位置为 {@code null};
     *                数组与物品都会被克隆, 之后改动原数组不影响Inventory.
     */
    AbstractInventory(@Nullable ItemStack @NotNull [] initial) {
        // 构造时就完成快照化: 先克隆再判空
        @Nullable ItemStack[] slots = new ItemStack[initial.length];
        for (int i = 0; i < initial.length; i++) {
            slots[i] = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(initial[i]));
        }
        this.state = slots;
        this.naturalOrder = SlotOrder.natural(initial.length);
    }

    /**
     * {@inheritDoc}
     *
     * <p>自持数据的Inventory自己就是根, 槽号原样使用.
     */
    @Override
    @NotNull
    SlotKey.Anchor resolveSlot(int slot) {
        return new SlotKey.Anchor(this, slot);
    }

    /**
     * 返回根Inventory槽位的最终物理身份;
     * 普通根Inventory的槽位自身就是终点.
     *
     * @param anchor 根Inventory槽位
     * @return 该槽的物理身份
     */
    @NotNull
    SlotKey rootPhysicalKey(@NotNull SlotKey.Anchor anchor) {
        return anchor;
    }

    /**
     * {@inheritDoc}
     *
     * <p>自持数据的Inventory只有它自己一个根.
     */
    @Override
    void collectRoots(@NotNull LinkedHashSet<AbstractInventory> roots) {
        roots.add(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return this.state.length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public ItemStack itemAt(int slot) {
        // 先把 volatile 引用抓到局部变量
        @Nullable ItemStack[] snapshot = this.state;
        return ItemUtils.copyOrNull(snapshot[slot]);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable ItemStack @NotNull [] snapshot() {
        // 先把 volatile 引用抓到局部变量
        @Nullable ItemStack[] snapshot = this.state;
        @Nullable ItemStack[] copy = new ItemStack[snapshot.length];
        for (int i = 0; i < snapshot.length; i++) {
            copy[i] = ItemUtils.copyOrNull(snapshot[i]);
        }
        return copy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>缺省实现所有槽位统一返回 {@link #DEFAULT_MAX_STACK_SIZE}.
     */
    @Override
    public int slotMaxStackSize(int slot) {
        Objects.checkIndex(slot, this.size());
        return DEFAULT_MAX_STACK_SIZE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>缺省实现始终返回构造时建立的自然顺序.
     */
    @Override
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        return this.naturalOrder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>本类就是事务终点, 直通自身状态:
     * 只有一个根, 快照即当前状态, 无需逐槽换算.
     */
    @NotNull
    @Override
    PlanContext openPlan() {
        @Nullable ItemStack[] planned = this.currentState();
        return new PlanContext(planned, deltas -> deltas.isEmpty()
                ? List.of()
                : List.of(new InventoryTransactions.Scope(this, planned, deltas)), ignoredSlot -> true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>直通自身状态, 写前准备只对本根做一次.
     */
    @NotNull
    @Override
    PlanContext openPlanForWrite() {
        boolean writable = this.prepareWrite();
        @Nullable ItemStack[] planned = this.currentState();
        return new PlanContext(
                planned,
                deltas -> deltas.isEmpty()
                        ? List.of()
                        : List.of(new InventoryTransactions.Scope(this, planned, deltas)),
                ignoredSlot -> writable
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>自持数据的Inventory没有外部来源, 什么都不做;
     */
    @Override
    public void refresh() {
    }

    /**
     * 判断当前线程能不能访问本Inventory背后的真实数据. 自持数据的Inventory永远可用;
     * ReferencingInventory按目标容器的持有者动态判断.
     *
     * @return 可访问返回 {@code true}
     */
    boolean writeAvailable() {
        return true;
    }

    /**
     * 为一次写规划做准备: 先确认当前线程可访问, 再触发写前同步.
     *
     * @return 本根当前能不能参与写事务
     */
    final boolean prepareWrite() {
        if (!this.writeAvailable()) {
            return false;
        }
        this.beforePlan();
        return true;
    }

    /**
     * 写前的根级同步钩子: 任何写入口在读规划快照之前都会经过这里, 不管调用来自本Inventory
     * 的公开方法还是视图的批量换算. simulate 等纯读路径不会触发.
     */
    void beforePlan() {
    }

    /**
     * 提交后的根级钩子: 事务引擎提交成功之后, post 事件派发之前, 对每个参与的根携带
     * 它的槽位变更调用一次.
     * <p>ReferencingInventory 在这里把变更写回外部容器, 因为这必须先于 post 事件派发,
     * 保证观察者在事件里重入写入时外部状态已经同步;
     *
     * @param deltas 本次事务在该根上的槽位变更
     */
    void afterCommit(@NotNull List<SlotDelta> deltas) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public TransactionResult setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        return this.commitSingle(reason, slot, item, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public TransactionResult forceSetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        return this.commitSingle(reason, slot, item, true);
    }

    /**
     * 提交一次单槽覆盖写入, {@link #setItem} 与 {@link #forceSetItem} 共用.
     *
     * @param reason 变更原因
     * @param slot 槽号
     * @param item 新物品, {@code null} 表示清空
     * @param bypassPre 为 {@code true} 时跳过 pre 观察者
     * @return 事务结果
     */
    private TransactionResult commitSingle(UpdateReason reason, int slot, @Nullable ItemStack item, boolean bypassPre) {
        Objects.checkIndex(slot, this.size());
        if (!this.prepareWrite()) {
            return TransactionResult.Unavailable.INSTANCE;
        }
        @Nullable ItemStack[] planned = this.currentState();
        SlotDelta delta = new SlotDelta(slot, planned[slot], item);
        return InventoryTransactions.commit(
                reason,
                List.of(new InventoryTransactions.Scope(this, planned, List.of(delta))),
                bypassPre
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item) {
        // 越界检查先于空输入短路生效, 行为不随物品内容摇摆
        Objects.checkIndex(slot, this.size());
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        if (!this.prepareWrite()) {
            return new AddResult(TransactionResult.Unavailable.INSTANCE, input.getAmount());
        }
        @Nullable ItemStack[] planned = this.currentState();
        @Nullable ItemStack current = planned[slot];
        int amount = input.getAmount();

        // 计算本槽还能接纳多少: 空槽看有效上限, 相似堆看剩余空间, 不相似一个不接纳
        int space;
        if (current == null) {
            space = Math.min(this.slotMaxStackSize(slot), input.getMaxStackSize());
        } else if (ItemUtils.isSimilar(current, input)) {
            space = Math.min(this.slotMaxStackSize(slot), current.getMaxStackSize()) - current.getAmount();
        } else {
            return new AddResult(EMPTY_COMMITTED, amount);
        }
        int moved = Math.clamp(space, 0, amount);
        if (moved == 0) {
            return new AddResult(EMPTY_COMMITTED, amount);
        }

        ItemStack after = current != null ? current.clone() : input.clone();
        after.setAmount((current != null ? current.getAmount() : 0) + moved);
        TransactionResult result = this.commitScoped(reason, planned, List.of(new SlotDelta(slot, current, after)));
        return new AddResult(result, result instanceof TransactionResult.Committed ? amount - moved : amount);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        Objects.checkIndex(slot, this.size());
        if (!this.prepareWrite()) {
            return TransactionResult.Unavailable.INSTANCE;
        }
        @Nullable ItemStack[] planned = this.currentState();
        // modifier 收到克隆, 在锁外执行; 其返回值经 SlotDelta 构造再次归一化与克隆
        @Nullable ItemStack modified = modifier.apply(ItemUtils.copyOrNull(planned[slot]));
        return this.commitScoped(reason, planned, List.of(new SlotDelta(slot, planned[slot], modified)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change) {
        Objects.checkIndex(slot, this.size());
        if (!this.prepareWrite()) {
            return TransactionResult.Unavailable.INSTANCE;
        }
        @Nullable ItemStack[] planned = this.currentState();
        @Nullable ItemStack current = planned[slot];
        if (current == null || change == 0) {
            return EMPTY_COMMITTED;
        }

        // 减量只受下限 0 约束, 上限钳制绝不作用于减量 —— 否则权威写入的超上限堆
        // 会在"减 1"时被静默压回上限, 凭空销毁物品. long 算术防止 int 边界溢出.
        long desired = (long) current.getAmount() + change;
        int target;
        if (change < 0) {
            target = (int) Math.max(0L, desired);
        } else {
            int cap = Math.min(this.slotMaxStackSize(slot), current.getMaxStackSize());
            if (current.getAmount() >= cap) {
                return EMPTY_COMMITTED;
            }
            target = (int) Math.min(desired, cap);
        }
        if (target == current.getAmount()) {
            return EMPTY_COMMITTED;
        }
        @Nullable ItemStack after = target > 0 ? ItemUtils.copyWithAmount(current, target) : null;
        return this.commitScoped(reason, planned, List.of(new SlotDelta(slot, current, after)));
    }

    /**
     * 把本根上已规划好的变更作为单根事务提交.
     *
     * @param reason 变更原因
     * @param planned 规划时读取的快照引用, 提交时用它做并发校验
     * @param deltas 槽位变更
     * @return 事务结果
     */
    private TransactionResult commitScoped(UpdateReason reason, @Nullable ItemStack[] planned, List<SlotDelta> deltas) {
        return InventoryTransactions.commit(
                reason,
                List.of(new InventoryTransactions.Scope(this, planned, deltas)),
                false
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public Subscription subscribePreUpdate(@NotNull Observer<? super TransactionPreEvent> observer) {
        return this.preUpdates.subscribe(observer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public Subscription subscribePostUpdate(@NotNull Observer<? super TransactionPostEvent> observer) {
        return this.postUpdates.subscribe(observer);
    }

    /**
     * 返回本Inventory的锁序号, 跨 Inventory 事务按它确定加锁顺序.
     *
     * @return 锁序号
     */
    long lockOrder() {
        return this.lockOrder;
    }

    /**
     * 返回本Inventory的写锁, 由事务引擎使用.
     *
     * @return 写锁
     */
    @NotNull
    ReentrantLock writeLock() {
        return this.writeLock;
    }

    /**
     * 返回当前快照的引用本身(不是克隆): 规划以它为基准, 提交时比对它有没有被换掉,
     * 以此发现并发冲突.
     *
     * @return 当前快照引用
     */
    @Nullable
    ItemStack @NotNull [] currentState() {
        return this.state;
    }

    /**
     * 把当前快照换成新数组 —— 只允许在持有写锁时调用.
     *
     * @param newState 新的不可变快照
     */
    void swapState(@Nullable ItemStack @NotNull [] newState) {
        this.state = newState;
    }

    /**
     * 在锁外向本Inventory的 pre 观察者派发事件;
     * 观察者的异常会被隔离并上报, 不会打断事务.
     *
     * @param event 提交前事件
     */
    void publishPreUpdate(@NotNull TransactionPreEvent event) {
        try {
            this.preUpdates.publish(event);
        } catch (Throwable exception) {
            // 捕获 Throwable: 观察者抛出的 Error(比如测试里的 AssertionError)同样不能
            // 逃逸给提交者, 否则事务结果与异常并存, 调用方无从判断到底成没成
            SparrowUI.getInstance().handleException("Failed to handle Inventory pre-update", exception);
        }
    }

    /**
     * 把提交后事件放入待派发队列;
     * 只允许在持有写锁时调用, 队列顺序因此与提交顺序一致.
     *
     * @param event 提交后事件
     */
    void enqueuePostEvent(@NotNull TransactionPostEvent event) {
        this.pendingPostEvents.add(event);
    }

    /**
     * 由提交者线程把队列里的 post 事件按提交顺序派发完.
     * <p>提交者在放锁之后调用本方法: 抢到"正在派发"标志的线程负责把队列发完为止,
     * 其他提交者把事件入队后就可以直接返回 —— 派发不会堵住后面的提交,
     * 而且同一个Inventory的事件顺序始终等于提交顺序.
     */
    void drainPostEvents() {
        while (this.drainingPostEvents.compareAndSet(false, true)) {
            try {
                // 抢到标志的线程独占派发: 逐个出队, 单个观察者的异常隔离上报, 不中断整个派发.
                TransactionPostEvent event;
                while ((event = this.pendingPostEvents.poll()) != null) {
                    try {
                        this.postUpdates.publish(event);
                    } catch (Throwable exception) {
                        SparrowUI.getInstance().handleException("Failed to handle Inventory post-update", exception);
                    }
                }
            } finally {
                this.drainingPostEvents.set(false);
            }

            // 放下标志后再检查一次队列: 如果有事件恰好赶在退出间隙入队, 而入队线程没抢到标志,
            // 就由本线程再派发一轮, 保证事件不会滞留
            if (this.pendingPostEvents.isEmpty()) {
                break;
            }
        }
    }
}
