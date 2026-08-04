package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 持有 Sparrow 内部状态数组, 并参与事务加锁, 并发校验和状态交换的 Inventory.
 * <p>一次操作经过 ViewInventory 后, 最终会展开到一个或多个 RootInventory.
 * RootInventory 不等于 "最终真实容器": ReferencingInventory 也是 RootInventory,
 * 但它的内部状态是 Bukkit 容器内容在 Sparrow 中的一份镜像.
 */
public abstract non-sealed class RootInventory extends SparrowInventory {
    private static final AtomicLong LOCK_ORDER_SOURCE = new AtomicLong();   // 锁序号发号器, 每创建一个 Inventory 发一个号

    private final long lockOrder = LOCK_ORDER_SOURCE.getAndIncrement(); // 跨 Inventory 事务按这个序号决定加锁先后
    private final ReentrantLock writeLock = new ReentrantLock();        // 只用来串行化写操作, 临界区内全是纯内存操作
    private final SlotOrder naturalOrder;                               // 遍历顺序的缺省回退, 构造时按槽位数建一次
    private final CopyOnWriteArrayList<InventoryUpdateChannel> updateChannels = new CopyOnWriteArrayList<>(); // 所有能访问当前 RootInventory 且拥有订阅者的 Inventory 事务订阅器

    private volatile @Nullable ItemStack @NotNull [] state; // 当前内部状态版本, 数组和物品均归 Inventory 内部所有
    @Nullable private volatile Predicate<ItemStack> placementRule; // 容器全局物品放入规则, null 表示放行
    private volatile @Nullable Predicate<ItemStack> @NotNull [] placementRulesBySlot; // 容器槽位的物品放入规则, 非 null 时覆盖全局规则

    /**
     * 以给定数组为初始内容创建 Inventory.
     *
     * @param initial 初始槽位内容, 空槽位置为 {@code null}.
     */
    RootInventory(@Nullable ItemStack @NotNull [] initial) {
        // 构造时复制每个物品, 并把空物品转为 null
        @Nullable ItemStack[] slots = new ItemStack[initial.length];
        for (int i = 0; i < initial.length; i++) {
            slots[i] = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(initial[i]));
        }
        this.state = slots;
        this.naturalOrder = SlotOrder.natural(initial.length);
        @SuppressWarnings("unchecked")
        @Nullable Predicate<ItemStack>[] placementRulesBySlot = (Predicate<ItemStack>[]) new Predicate<?>[initial.length];
        this.placementRulesBySlot = placementRulesBySlot;
    }

    @NotNull
    @Override
    SlotKey.Anchor resolveSlot(int slot) {
        return new SlotKey.Anchor(this, slot);
    }

    /**
     * 返回 RootInventory 槽位对应的 SlotKey.
     *
     * @param anchor RootInventory 槽地址
     * @return 该槽的 SlotKey
     */
    @NotNull
    SlotKey rootPhysicalKey(@NotNull SlotKey.Anchor anchor) {
        return anchor;
    }

    @Override
    public int size() {
        return this.state.length;
    }

    @Override
    @Nullable
    public ItemStack itemAt(int slot) {
        // 先把 volatile 引用抓到局部变量
        @Nullable ItemStack[] snapshot = this.state;
        return ItemUtils.copyOrNull(snapshot[slot]);
    }

    @Override
    @Nullable
    public ItemStack unsafeItemAt(int slot) {
        // 先把 volatile 引用抓到局部变量
        @Nullable ItemStack[] snapshot = this.state;
        return snapshot[slot];
    }

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

    @Override
    public @Nullable ItemStack @NotNull [] unsafeSnapshot() {
        return this.state;
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

    /**
     * 替换适用于所有未声明逐槽规则的槽位放入规则.
     * 规则收到的是完整原始输入; 传入 {@code null} 表示这些槽位一律放行.
     * 规则异常会原样传播, 当前规划不会派发事件或提交事务.
     * <p>规则拿到的是零拷贝的内部引用, 同一次规划中会跨多个槽位复用同一个实例.
     * 规则只能读取它, 不得修改或保存引用; 违反约定会污染规划快照, 事件历史与后续读取结果.
     *
     * @param rule 新的全局放入规则, {@code null} 表示放行
     */
    public void setPlacementRule(@Nullable Predicate<@NotNull ItemStack> rule) {
        this.placementRule = rule;
    }

    /**
     * 返回当前的全局放入规则.
     *
     * @return 全局放入规则; 没有设置过时为 {@code null}, 表示这些槽位一律放行
     */
    @Nullable
    public Predicate<ItemStack> getPlacementRule() {
        return this.placementRule;
    }

    /**
     * 替换一个槽位的显式放入规则. 该规则完全覆盖全局规则;
     * 传入 {@code null} 会清除逐槽覆盖, 使该槽重新使用全局规则.
     * <p>与全局规则一样, 规则拿到的是零拷贝的内部引用, 只能读取, 不得修改或保存;
     * 详见 {@link #setPlacementRule(Predicate)}.
     *
     * @param slot 槽位序号
     * @param rule 新的逐槽放入规则, {@code null} 表示回退到全局规则
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public void setPlacementRule(int slot, @Nullable Predicate<@NotNull ItemStack> rule) {
        Objects.checkIndex(slot, this.size());
        @Nullable Predicate<ItemStack>[] placementRulesBySlot = this.placementRulesBySlot.clone();
        placementRulesBySlot[slot] = rule;
        this.placementRulesBySlot = placementRulesBySlot;
    }

    /**
     * 返回某个槽位的显式放入规则; 不含回退到的全局规则.
     *
     * @param slot 槽位序号
     * @return 该槽的逐槽放入规则; 没有覆盖时为 {@code null}, 表示这个槽用的是全局规则
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    public Predicate<ItemStack> getPlacementRule(int slot) {
        Objects.checkIndex(slot, this.size());
        return this.placementRulesBySlot[slot];
    }

    @NotNull
    @Override
    IntPredicate placementPredicate(@NotNull ItemStack item) {
        @Nullable Predicate<ItemStack> placementRule = this.placementRule;
        @Nullable Predicate<ItemStack>[] placementRulesBySlot = this.placementRulesBySlot;
        return slot -> {
            @Nullable Predicate<ItemStack> rule = placementRulesBySlot[slot];
            if (rule == null) {
                rule = placementRule;
            }
            return rule == null || rule.test(item);
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>本类直接持有事务状态, 规划内容就是当前内部状态版本, 无需逐槽换算.
     */
    @NotNull
    @Override
    PlanContext openPlan() {
        @Nullable ItemStack[] planned = this.currentState();
        return new PlanContext(planned, deltas -> deltas.isEmpty()
                ? List.of()
                : List.of(new TransactionScope(this, planned, deltas)), List.of(new PlannedRoot(this, planned)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接使用自身状态, 写前准备只对当前 RootInventory 做一次.
     */
    @NotNull
    @Override
    PlanContext openPlanForWrite() {
        this.prepareWrite();
        return this.openPlan();
    }

    @Override
    public void refresh() {
    }

    /**
     * 为一次写规划做准备, 触发写前同步.
     * 任何写入口在读取规划内容之前都会经过这里, simulate 等纯读路径不会触发.
     */
    void prepareWrite() {
    }

    /**
     * 事务提交成功之后, post 事件派发之前, 对每个参与的 RootInventory 携带其槽位变更调用一次.
     * <p>ReferencingInventory 在这里把变更写回外部容器, 因为这必须先于 post 事件派发,
     * 保证观察者在事件里重入写入时外部状态已经同步. 此方法抛出的异常会直接传播,
     * 此时 Sparrow 内部状态已经提交;
     *
     * @param deltas 本次事务在当前 RootInventory 上的槽位变更
     */
    void afterCommit(@NotNull List<SlotChange> deltas) {
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
        this.prepareWrite();
        @Nullable ItemStack[] planned = this.currentState();
        SlotChange delta = new SlotChange(slot, planned[slot], item);
        return InventoryTransactions.commit(
                reason,
                List.of(new TransactionScope(this, planned, List.of(delta))),
                bypassPre
        );
    }

    @Override
    @NotNull
    public AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item) {
        // 越界检查先于空输入短路生效, 行为不随物品内容摇摆
        Objects.checkIndex(slot, this.size());
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        this.prepareWrite();
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
        if (moved == 0 || !this.placementPredicate(input).test(slot)) {
            return new AddResult(EMPTY_COMMITTED, amount);
        }

        ItemStack after = current != null ? current.clone() : input.clone();
        after.setAmount((current != null ? current.getAmount() : 0) + moved);
        TransactionResult result = this.commitScoped(reason, planned, List.of(new SlotChange(slot, current, after)));
        return new AddResult(result, result instanceof TransactionResult.Committed ? amount - moved : amount);
    }

    @Override
    @NotNull
    public TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        Objects.checkIndex(slot, this.size());
        this.prepareWrite();
        @Nullable ItemStack[] planned = this.currentState();
        // modifier 收到物品副本并在锁外执行; SlotChange 会再次复制返回值, 并把空物品转为 null
        @Nullable ItemStack modified = modifier.apply(ItemUtils.copyOrNull(planned[slot]));
        return this.commitScoped(reason, planned, List.of(new SlotChange(slot, planned[slot], modified)));
    }

    @Override
    @NotNull
    public TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change) {
        Objects.checkIndex(slot, this.size());
        this.prepareWrite();
        @Nullable ItemStack[] planned = this.currentState();
        @Nullable ItemStack current = planned[slot];
        if (current == null || change == 0) {
            return EMPTY_COMMITTED;
        }

        // 减量只受下限 0 约束, 上限钳制绝不作用于减量 —— 否则直接写入的超上限堆
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
        return this.commitScoped(reason, planned, List.of(new SlotChange(slot, current, after)));
    }

    /**
     * 把当前 RootInventory 上已规划好的变更作为单 RootInventory 事务提交.
     *
     * @param reason 变更原因
     * @param planned 规划基准状态引用, 提交时用它做并发校验
     * @param deltas 槽位变更
     * @return 事务结果
     */
    private TransactionResult commitScoped(UpdateReason reason, @Nullable ItemStack[] planned, List<SlotChange> deltas) {
        return InventoryTransactions.commit(
                reason,
                List.of(new TransactionScope(this, planned, deltas)),
                false
        );
    }

    /**
     * 本 Inventory 的锁序号, 跨 Inventory 事务按它确定加锁顺序.
     *
     * @return 锁序号
     */
    long lockOrder() {
        return this.lockOrder;
    }

    /**
     * 本 Inventory 的写锁, 由事务引擎使用.
     *
     * @return 写锁
     */
    @NotNull
    ReentrantLock writeLock() {
        return this.writeLock;
    }

    /**
     * 返回当前内部状态版本的引用本身:
     * 规划以它为基准, 提交时比对它有没有被换掉, 以此发现并发冲突.
     *
     * @return 当前内部状态版本引用
     */
    @Nullable
    ItemStack @NotNull [] currentState() {
        return this.state;
    }

    /**
     * 把当前内部状态版本换成新数组, 只允许在持有写锁时调用.
     *
     * @param newState 新的内部状态版本
     */
    void swapState(@Nullable ItemStack @NotNull [] newState) {
        this.state = newState;
    }

    /**
     * 登记一个能够访问当前 RootInventory 的 Inventory 事务订阅器.
     * <p>同一订阅器也可能登记在其他 RootInventory 上; 事务收集阶段会按订阅器实例身份去重.
     *
     * @param channel 要登记的 Inventory 事务订阅器
     */
    void addUpdateChannel(@NotNull InventoryUpdateChannel channel) {
        this.updateChannels.add(channel);
    }

    /**
     * 撤销一个不再拥有任何订阅者的 Inventory 事务订阅器.
     *
     * @param channel 要撤销的 Inventory 事务订阅器
     */
    void removeUpdateChannel(@NotNull InventoryUpdateChannel channel) {
        this.updateChannels.remove(channel);
    }

    /**
     * 把当前 RootInventory 上仍然活动的 Inventory 事务订阅器追加到事务级结果中.
     * <p>跨 RootInventory 的 CompositeInventory 会让同一订阅器出现在多个 RootInventory 的索引里,
     * {@code seen} 使用订阅器实例身份消除重复, 保证同一订阅器在一笔事务中只准备一次当前 Inventory 槽位变更.
     *
     * @param channels 按首次遇到顺序收集订阅器的事务级列表
     * @param seen 事务级订阅器实例身份集合
     */
    void collectUpdateChannels(
            @NotNull List<InventoryUpdateChannel> channels,
            @NotNull IdentityHashMap<InventoryUpdateChannel, Boolean> seen
    ) {
        // CopyOnWriteArrayList 的独立数组快照避免安装或卸载与本次遍历相互干扰.
        InventoryUpdateChannel[] snapshot = this.updateChannels.toArray(InventoryUpdateChannel[]::new);
        for (int i = 0; i < snapshot.length; i++) {
            InventoryUpdateChannel channel = snapshot[i];
            if (channel.isActive() && seen.put(channel, Boolean.TRUE) == null) {
                channels.add(channel);
            }
        }
    }
}
