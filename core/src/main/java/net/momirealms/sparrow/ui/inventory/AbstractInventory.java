package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 库存实现的并发骨架: 不可变快照 + 短写锁.
 * <p>槽状态收敛为一个 volatile 引用指向的数组快照; 数组创建后永不修改, 替换状态
 * 就是换引用, 这次引用交换即事务的线性化点. 读路径直接取当前快照, 完全无锁;
 * 写路径由 {@link InventoryTransactions} 在写锁内完成校验与交换, 用户回调一律
 * 在锁外执行.
 * <p>全部写方法族在这里以"plan 当前快照 + 提交事务"的方式实现; 子类只需提供
 * 堆叠上限与迭代顺序等配置钩子.
 * <p>库存实例需经安全发布(final 字段, volatile, 锁或线程启动边界)交给其他线程;
 * 经普通字段的裸发布不在内存模型保障范围内.
 */
abstract class AbstractInventory implements Inventory {
    private static final AtomicLong LOCK_ORDER_SOURCE = new AtomicLong(); // 进程内锁序号发号器
    private static final TransactionResult.Committed EMPTY_COMMITTED = new TransactionResult.Committed(List.of()); // 无变更操作的共享成功结果, 不派发事件

    private final long lockOrder = LOCK_ORDER_SOURCE.getAndIncrement(); // 跨库存事务的全序加锁依据
    private final ReentrantLock writeLock = new ReentrantLock(); // 仅串行化写者, 临界区为纯内存操作
    private final SlotOrder naturalOrder; // 迭代顺序的缺省回退, 构造时按尺寸建立一次

    private final ObservableDispatcher<TransactionPreEvent> preUpdates = new ObservableDispatcher<>();
    private final ObservableDispatcher<TransactionPostEvent> postUpdates = new ObservableDispatcher<>();
    private final ConcurrentLinkedQueue<TransactionPostEvent> pendingPostEvents = new ConcurrentLinkedQueue<>(); // 锁内入队保证顺序 = 提交顺序
    private final AtomicBoolean drainingPostEvents = new AtomicBoolean(); // 排水者标志, 同一时刻至多一个线程派发

    private volatile @Nullable ItemStack @NotNull [] state; // 当前不可变快照, 元素归内部所有
    private volatile int guiPriority; // 快速转移与收集的目标排序键, 弱一致配置

    AbstractInventory(@Nullable ItemStack @NotNull [] initial) {
        // 构造即快照化: 先克隆再归一化 —— 判空针对私有克隆进行, 调用方并发修改原物品也无法把 AIR/零数量实例走私进内部快照
        @Nullable ItemStack[] slots = new ItemStack[initial.length];
        for (int i = 0; i < initial.length; i++) {
            slots[i] = ItemUtils.nullIfEmpty(ItemUtils.cloneOrNull(initial[i]));
        }
        this.state = slots;
        this.naturalOrder = SlotOrder.natural(initial.length);
    }

    @Override
    public int size() {
        return this.state.length;
    }

    @Override
    @Nullable
    public ItemStack itemAt(int slot) {
        @Nullable ItemStack[] snapshot = this.state;
        return ItemUtils.cloneOrNull(snapshot[slot]);
    }

    // 先把 volatile 引用捕获到局部变量再遍历: 全程只读同一个快照, 写者并发 swap 不会
    // 造成新旧元素混排的撕裂视图; 直接反复读 this.state 会破坏一致性契约
    @Override
    public @Nullable ItemStack @NotNull [] snapshot() {
        @Nullable ItemStack[] snapshot = this.state;
        @Nullable ItemStack[] copy = new ItemStack[snapshot.length];
        for (int i = 0; i < snapshot.length; i++) {
            copy[i] = ItemUtils.cloneOrNull(snapshot[i]);
        }
        return copy;
    }

    @Override
    public int slotMaxStackSize(int slot) {
        Objects.checkIndex(slot, this.size());
        return DEFAULT_MAX_STACK_SIZE;
    }

    @Override
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        return this.naturalOrder;
    }

    @Override
    public int guiPriority() {
        return this.guiPriority;
    }

    /**
     * 设置快速转移与收集语义选择目标库存的排序键, 值大者优先.
     */
    public void guiPriority(int guiPriority) {
        this.guiPriority = guiPriority;
    }

    @Override
    @NotNull
    public TransactionResult setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        return this.commitSingle(reason, slot, item, false);
    }

    @Override
    @NotNull
    public TransactionResult forceSetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        return this.commitSingle(reason, slot, item, true);
    }

    private TransactionResult commitSingle(UpdateReason reason, int slot, @Nullable ItemStack item, boolean bypassPre) {
        @Nullable ItemStack[] planned = this.currentState();
        SlotDelta delta = new SlotDelta(slot, planned[slot], item);
        return InventoryTransactions.commit(
                reason,
                List.of(new InventoryTransactions.Scope(this, planned, List.of(delta))),
                bypassPre
        );
    }

    @Override
    @NotNull
    public AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item) {
        // 越界契约先于空输入短路生效, 行为不随物品内容摇摆
        Objects.checkIndex(slot, this.size());
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.cloneOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        @Nullable ItemStack[] planned = this.currentState();
        @Nullable ItemStack current = planned[slot];
        int amount = input.getAmount();

        // 计算本槽还能接纳的数量: 空槽看有效上限, 相似堆看剩余空间, 不相似不接纳
        int space;
        if (current == null) {
            space = this.effectiveMaxStackSize(slot, input);
        } else if (ItemUtils.isSimilar(current, input)) {
            space = this.effectiveMaxStackSize(slot, current) - current.getAmount();
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

    @Override
    @NotNull
    public TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        @Nullable ItemStack[] planned = this.currentState();
        // modifier 收到克隆, 在锁外执行; 其返回值经 SlotDelta 构造再次归一化与克隆
        @Nullable ItemStack modified = modifier.apply(ItemUtils.cloneOrNull(planned[slot]));
        return this.commitScoped(reason, planned, List.of(new SlotDelta(slot, planned[slot], modified)));
    }

    @Override
    @NotNull
    public TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change) {
        @Nullable ItemStack[] planned = this.currentState();
        @Nullable ItemStack current = planned[slot];
        if (current == null || change == 0) {
            return EMPTY_COMMITTED;
        }

        // 减量只受下限 0 约束, 上限钳制绝不作用于减量 —— 否则权威写入的超上限堆
        // 会在"减 1"时被静默钳回上限, 凭空销毁物品. long 算术防止 int 边界溢出.
        long desired = (long) current.getAmount() + change;
        int target;
        if (change < 0) {
            target = (int) Math.max(0L, desired);
        } else {
            int cap = this.effectiveMaxStackSize(slot, current);
            if (current.getAmount() >= cap) {
                return EMPTY_COMMITTED;
            }
            target = (int) Math.min(desired, cap);
        }
        if (target == current.getAmount()) {
            return EMPTY_COMMITTED;
        }
        @Nullable ItemStack after = target > 0 ? withAmount(current, target) : null;
        return this.commitScoped(reason, planned, List.of(new SlotDelta(slot, current, after)));
    }

    @Override
    @NotNull
    public AddResult add(@NotNull UpdateReason reason, @NotNull ItemStack item) {
        // 先克隆再归一化: 隔离调用方并发修改, 且判空结论与后续读取永远一致
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.cloneOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        @Nullable ItemStack[] planned = this.currentState();
        AddPlan plan = this.planAdd(planned, input);
        if (plan.deltas().isEmpty()) {
            return new AddResult(EMPTY_COMMITTED, plan.remaining());
        }
        TransactionResult result = this.commitScoped(reason, planned, plan.deltas());
        return new AddResult(result, result instanceof TransactionResult.Committed ? plan.remaining() : input.getAmount());
    }

    @Override
    @NotNull
    public CollectResult collect(@NotNull UpdateReason reason, @NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.cloneOrNull(template));
        if (sample == null || upTo <= 0) {
            return new CollectResult(EMPTY_COMMITTED, 0);
        }
        @Nullable ItemStack[] planned = this.currentState();
        TakePlan plan = this.planCollect(planned, sample, upTo);
        if (plan.deltas().isEmpty()) {
            return new CollectResult(EMPTY_COMMITTED, 0);
        }
        TransactionResult result = this.commitScoped(reason, planned, plan.deltas());
        return new CollectResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    @Override
    @NotNull
    public RemoveResult remove(@NotNull UpdateReason reason, @NotNull Predicate<@NotNull ItemStack> matcher, int upTo) {
        if (upTo <= 0) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        @Nullable ItemStack[] planned = this.currentState();
        TakePlan plan = this.planRemove(planned, matcher, upTo);
        if (plan.deltas().isEmpty()) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        TransactionResult result = this.commitScoped(reason, planned, plan.deltas());
        return new RemoveResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    @Override
    public int simulateAdd(@NotNull ItemStack item) {
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.cloneOrNull(item));
        if (input == null) {
            return 0;
        }
        return this.planAdd(this.currentState(), input).remaining();
    }

    @Override
    public int simulateCollect(@NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.cloneOrNull(template));
        if (sample == null || upTo <= 0) {
            return 0;
        }
        return this.planCollect(this.currentState(), sample, upTo).taken();
    }

    @Override
    public boolean canHold(@NotNull ItemStack item) {
        return this.simulateAdd(item) == 0;
    }

    /**
     * 批量放入的两遍规划: 先把相似且未满的堆填到有效上限, 再按顺序占用空槽.
     * 与 simulate 共享同一实现, 数量守恒由结构保证.
     */
    private AddPlan planAdd(@Nullable ItemStack[] snapshot, ItemStack item) {
        List<SlotDelta> deltas = new ArrayList<>();
        int remaining = item.getAmount();
        SlotOrder order = this.iterationOrder(OperationCategory.ADD);

        // 第一遍: 合并到相似且未满的堆
        for (int i = 0; i < order.size() && remaining > 0; i++) {
            int slot = order.slotAt(i);
            @Nullable ItemStack current = snapshot[slot];
            if (current == null || !ItemUtils.isSimilar(current, item)) {
                continue;
            }
            int space = this.effectiveMaxStackSize(slot, current) - current.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            deltas.add(new SlotDelta(slot, current, withAmount(current, current.getAmount() + moved)));
            remaining -= moved;
        }

        // 第二遍: 占用空槽
        for (int i = 0; i < order.size() && remaining > 0; i++) {
            int slot = order.slotAt(i);
            if (snapshot[slot] != null) {
                continue;
            }
            int moved = Math.min(this.effectiveMaxStackSize(slot, item), remaining);
            if (moved <= 0) {
                continue;
            }
            deltas.add(new SlotDelta(slot, null, withAmount(item, moved)));
            remaining -= moved;
        }
        return new AddPlan(deltas, remaining);
    }

    /**
     * 批量收集的两遍规划: 先收取非满堆的零头保持满堆完整, 不足再动满堆.
     * 快照在规划期间不变, 用 touched 防止同一槽被两遍重复收取.
     */
    private TakePlan planCollect(@Nullable ItemStack[] snapshot, ItemStack template, int upTo) {
        List<SlotDelta> deltas = new ArrayList<>();
        int taken = 0;
        SlotOrder order = this.iterationOrder(OperationCategory.COLLECT);
        boolean[] touched = new boolean[snapshot.length];

        for (int pass = 0; pass < 2 && taken < upTo; pass++) {
            boolean wantFullStacks = pass == 1;
            for (int i = 0; i < order.size() && taken < upTo; i++) {
                int slot = order.slotAt(i);
                @Nullable ItemStack current = snapshot[slot];
                if (touched[slot] || current == null || !ItemUtils.isSimilar(current, template)) {
                    continue;
                }
                boolean fullStack = current.getAmount() >= this.effectiveMaxStackSize(slot, current);
                if (fullStack != wantFullStacks) {
                    continue;
                }
                int take = Math.min(current.getAmount(), upTo - taken);
                deltas.add(new SlotDelta(slot, current, reduced(current, take)));
                touched[slot] = true;
                taken += take;
            }
        }
        return new TakePlan(deltas, taken);
    }

    /**
     * 批量移除的规划: 按 OTHER 顺序逐槽把 matcher 命中的物品扣减到目标数量.
     */
    private TakePlan planRemove(@Nullable ItemStack[] snapshot, Predicate<@NotNull ItemStack> matcher, int upTo) {
        List<SlotDelta> deltas = new ArrayList<>();
        int taken = 0;
        SlotOrder order = this.iterationOrder(OperationCategory.OTHER);
        for (int i = 0; i < order.size() && taken < upTo; i++) {
            int slot = order.slotAt(i);
            @Nullable ItemStack current = snapshot[slot];
            // matcher 是用户代码, 只允许它接触克隆
            if (current == null || !matcher.test(current.clone())) {
                continue;
            }
            int take = Math.min(current.getAmount(), upTo - taken);
            deltas.add(new SlotDelta(slot, current, reduced(current, take)));
            taken += take;
        }
        return new TakePlan(deltas, taken);
    }

    private TransactionResult commitScoped(UpdateReason reason, @Nullable ItemStack[] planned, List<SlotDelta> deltas) {
        return InventoryTransactions.commit(
                reason,
                List.of(new InventoryTransactions.Scope(this, planned, deltas)),
                false
        );
    }

    // 放入类算法的有效上限 = min(槽上限, 物品自身上限)
    private int effectiveMaxStackSize(int slot, ItemStack item) {
        return Math.min(this.slotMaxStackSize(slot), item.getMaxStackSize());
    }

    private static ItemStack withAmount(ItemStack source, int amount) {
        ItemStack copy = source.clone();
        copy.setAmount(amount);
        return copy;
    }

    @Nullable
    private static ItemStack reduced(ItemStack current, int take) {
        int left = current.getAmount() - take;
        return left > 0 ? withAmount(current, left) : null;
    }

    @Override
    @NotNull
    public Subscription subscribePreUpdate(@NotNull Observer<? super TransactionPreEvent> observer) {
        return this.preUpdates.subscribe(observer);
    }

    @Override
    @NotNull
    public Subscription subscribePostUpdate(@NotNull Observer<? super TransactionPostEvent> observer) {
        return this.postUpdates.subscribe(observer);
    }

    long lockOrder() {
        return this.lockOrder;
    }

    @NotNull
    ReentrantLock writeLock() {
        return this.writeLock;
    }

    // 返回当前快照引用本身: plan 以它为基准, commit 以 identity 比对完成乐观校验
    @Nullable
    ItemStack @NotNull [] currentState() {
        return this.state;
    }

    // 线性化点: 仅允许在持有写锁时调用
    void swapState(@Nullable ItemStack @NotNull [] newState) {
        this.state = newState;
    }

    /**
     * 在锁外向本库存的 pre 观察者派发事件, 观察者异常隔离上报, 不中止事务.
     */
    void publishPreUpdate(@NotNull TransactionPreEvent event) {
        try {
            this.preUpdates.publish(event);
        } catch (Throwable exception) {
            // 捕获 Throwable: 观察者抛出的 Error(如测试环境的 AssertionError)同样不得
            // 逃逸给提交者, 否则事务结果与异常并存, 调用方无从判断
            SparrowUI.getInstance().handleException("Failed to handle Inventory pre-update", exception);
        }
    }

    // 仅允许在持有写锁时调用, 使队列顺序与快照交换顺序一致
    void enqueuePostEvent(@NotNull TransactionPostEvent event) {
        this.pendingPostEvents.add(event);
    }

    /**
     * 以"提交者线程排水"的方式按提交顺序派发 post 事件.
     * <p>提交者在放锁后调用: 抢到排水者标志的线程负责把队列按序发完, 其余提交者
     * 入队即返回, 因此派发不阻塞后续提交, 且同一库存的事件顺序始终等于提交顺序.
     */
    void drainPostEvents() {
        while (this.drainingPostEvents.compareAndSet(false, true)) {
            try {
                // 独占排水: 逐个出队派发, 单个观察者的异常隔离上报, 不中断排水.
                // 捕获 Throwable 而非 RuntimeException: Error 逃逸会把异常抛给无辜的
                // 提交者(事务实际已提交), 并让队列中剩余事件滞留到下一次提交
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

            // 释放标志后复查: 若有事件恰在退出间隙入队且对方未能抢到标志, 由本线程重新排水
            if (this.pendingPostEvents.isEmpty()) {
                break;
            }
        }
    }

    /** 放入规划的产物: 变更集与放不下的余量. */
    private record AddPlan(List<SlotDelta> deltas, int remaining) {
    }

    /** 收集与移除规划的产物: 变更集与实际取出的数量. */
    private record TakePlan(List<SlotDelta> deltas, int taken) {
    }
}
