package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.CollectResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 全部内建库存的共同骨架: 把任意逻辑槽解析到自持数据的根库存槽.
 * <p>视图家族(拼接, 遮蔽)不自持槽数据, 它们的读写经 {@link #resolveSlot(int)} 归约
 * 到底层 {@link AbstractInventory}; 批量操作在逻辑快照上规划后按根库存拆分事务范围,
 * 由 {@link InventoryTransactions} 以多锁全序保证跨根全成全败.
 * <p>视图不是独立事件源: 订阅即对全部根库存的转发订阅. 跨根事务的事件会按参与根
 * 数量投递多次(同一事件对象), 需要精确一次的观察者以事件对象 identity 判重.
 */
abstract class SparrowInventory implements Inventory {
    static final TransactionResult.Committed EMPTY_COMMITTED = new TransactionResult.Committed(List.of()); // 无变更操作的共享成功结果, 不派发事件

    // 三类操作各自的目标排序键, 弱一致配置; null 表示未显式设置, 取值回退 fallbackGuiPriority.
    // 用 Integer 而不是 int 是因为 0 是合法优先级, 不能拿它当"未设置"的哨兵
    @Nullable private volatile Integer addGuiPriority;
    @Nullable private volatile Integer collectGuiPriority;
    @Nullable private volatile Integer otherGuiPriority;
    // 懒加载的稳定适配器单例, Bukkit 侧以引用身份关联库存
    @Nullable private volatile org.bukkit.inventory.Inventory bukkitView;

    /**
     * 逻辑槽在自持数据根库存中的落点.
     */
    record Anchor(@NotNull AbstractInventory root, int rootSlot) {
    }

    /**
     * 把逻辑槽解析到根库存槽; 自持数据的实现返回自身.
     */
    @NotNull
    abstract Anchor resolveSlot(int slot);

    /**
     * 按遍历序收集全部去重后的根库存, 用于订阅转发与事务参与者展开.
     */
    abstract void collectRoots(@NotNull LinkedHashSet<AbstractInventory> roots);

    @Override
    public int guiPriority(@NotNull OperationCategory category) {
        Integer explicit = switch (category) {
            case ADD -> this.addGuiPriority;
            case COLLECT -> this.collectGuiPriority;
            case OTHER -> this.otherGuiPriority;
        };
        return explicit != null ? explicit : this.fallbackGuiPriority(category);
    }

    /**
     * 未显式设置优先级时的回退取值.
     * <p>自持数据的库存没有可委托的对象, 回退为 0; 装饰视图覆写为透传底层取值,
     * 从而获得"未设置即跟随底层, 设置即遮盖"的语义.
     *
     * @param category 操作类型
     * @return 该操作类别的回退优先级
     */
    int fallbackGuiPriority(@NotNull OperationCategory category) {
        return 0;
    }

    /**
     * 设置指定操作下选择目标 Inventory 的优先级.
     *
     * @param category 操作类型
     * @param priority 优先级, 越大越先处理.
     */
    public void guiPriority(@NotNull OperationCategory category, int priority) {
        switch (category) {
            case ADD -> this.addGuiPriority = priority;
            case COLLECT -> this.collectGuiPriority = priority;
            case OTHER -> this.otherGuiPriority = priority;
        }
    }

    /**
     * 设置全部操作下选择目标 Inventory 的优先级.
     *
     * @param priority 优先级, 越大越先处理.
     */
    public void guiPriority(int priority) {
        this.addGuiPriority = priority;
        this.collectGuiPriority = priority;
        this.otherGuiPriority = priority;
    }

    /**
     * 清除指定操作的显式优先级, 使其恢复回退取值:
     * 自持库存回到 0, 装饰视图重新透传底层.
     *
     * @param category 操作类型
     */
    public void clearGuiPriority(@NotNull OperationCategory category) {
        switch (category) {
            case ADD -> this.addGuiPriority = null;
            case COLLECT -> this.collectGuiPriority = null;
            case OTHER -> this.otherGuiPriority = null;
        }
    }

    @Nullable
    @Override
    public ItemStack itemAt(int slot) {
        Anchor anchor = this.resolveSlot(slot);
        return anchor.root().itemAt(anchor.rootSlot());
    }

    @Override
    public int slotMaxStackSize(int slot) {
        Anchor anchor = this.resolveSlot(slot);
        return anchor.root().slotMaxStackSize(anchor.rootSlot());
    }

    @NotNull
    @Override
    public TransactionResult setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        Anchor anchor = this.resolveSlot(slot);
        return anchor.root().setItem(reason, anchor.rootSlot(), item);
    }

    @NotNull
    @Override
    public TransactionResult forceSetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        Anchor anchor = this.resolveSlot(slot);
        return anchor.root().forceSetItem(reason, anchor.rootSlot(), item);
    }

    @NotNull
    @Override
    public AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item) {
        Anchor anchor = this.resolveSlot(slot);
        return anchor.root().putItem(reason, anchor.rootSlot(), item);
    }

    @NotNull
    @Override
    public TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        Anchor anchor = this.resolveSlot(slot);
        return anchor.root().modifyItem(reason, anchor.rootSlot(), modifier);
    }

    @NotNull
    @Override
    public TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change) {
        Anchor anchor = this.resolveSlot(slot);
        return anchor.root().changeAmount(reason, anchor.rootSlot(), change);
    }

    @NotNull
    @Override
    public AddResult add(@NotNull UpdateReason reason, @NotNull ItemStack item) {
        // 先克隆再归一化: 隔离调用方并发修改, 且判空结论与后续读取永远一致
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        PlanContext context = this.openPlanForWrite();
        InventoryPlanner.AddPlan plan = InventoryPlanner.planAdd(context.snapshot(), input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize);
        if (plan.deltas().isEmpty()) {
            return new AddResult(EMPTY_COMMITTED, plan.remaining());
        }
        TransactionResult result = InventoryTransactions.commit(reason, context.scoper().apply(plan.deltas()), false);
        return new AddResult(result, result instanceof TransactionResult.Committed ? plan.remaining() : input.getAmount());
    }

    @NotNull
    @Override
    public CollectResult collect(@NotNull UpdateReason reason, @NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(template));
        if (sample == null || upTo <= 0) {
            return new CollectResult(EMPTY_COMMITTED, 0);
        }
        PlanContext context = this.openPlanForWrite();
        InventoryPlanner.TakePlan plan = InventoryPlanner.planCollect(context.snapshot(), sample, upTo, this.iterationOrder(OperationCategory.COLLECT), this::slotMaxStackSize);
        if (plan.deltas().isEmpty()) {
            return new CollectResult(EMPTY_COMMITTED, 0);
        }
        TransactionResult result = InventoryTransactions.commit(reason, context.scoper().apply(plan.deltas()), false);
        return new CollectResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    @NotNull
    @Override
    public RemoveResult remove(@NotNull UpdateReason reason, @NotNull Predicate<@NotNull ItemStack> matcher, int upTo) {
        if (upTo <= 0) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        PlanContext context = this.openPlanForWrite();
        InventoryPlanner.TakePlan plan = InventoryPlanner.planRemove(context.snapshot(), matcher, upTo, this.iterationOrder(OperationCategory.OTHER));
        if (plan.deltas().isEmpty()) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        TransactionResult result = InventoryTransactions.commit(reason, context.scoper().apply(plan.deltas()), false);
        return new RemoveResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    @Override
    public int simulateAdd(@NotNull ItemStack item) {
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return 0;
        }
        return InventoryPlanner.planAdd(this.openPlan().snapshot(), input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize).remaining();
    }

    @Override
    public int simulateCollect(@NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(template));
        if (sample == null || upTo <= 0) {
            return 0;
        }
        return InventoryPlanner.planCollect(this.openPlan().snapshot(), sample, upTo, this.iterationOrder(OperationCategory.COLLECT), this::slotMaxStackSize).taken();
    }

    @Override
    public boolean canHold(@NotNull ItemStack item) {
        return this.simulateAdd(item) == 0;
    }

    // 双检查锁定懒加载稳定的适配器单例: Bukkit 以 == 或 Map 键关联库存, 每次新建会破坏身份语义.
    @Override
    @NotNull
    public org.bukkit.inventory.Inventory asBukkitInventory() {
        org.bukkit.inventory.Inventory view = this.bukkitView;
        if (view == null) {
            synchronized (this) {
                view = this.bukkitView;
                if (view == null) {
                    view = new InventoryAdapter(this);
                    this.bukkitView = view;
                }
            }
        }
        return view;
    }

    @Override
    @NotNull
    public Subscription subscribePreUpdate(@NotNull Observer<? super TransactionPreEvent> observer) {
        return this.subscribeRoots(root -> root.subscribePreUpdate(observer));
    }

    @Override
    @NotNull
    public Subscription subscribePostUpdate(@NotNull Observer<? super TransactionPostEvent> observer) {
        return this.subscribeRoots(root -> root.subscribePostUpdate(observer));
    }

    /**
     * 一次批量规划的上下文: 逻辑快照与"逻辑变更集 → 事务范围"的拆分函数.
     * 快照型库存直通自身状态; 视图把逻辑槽逐一解析到根库存并按根分组.
     */
    record PlanContext(
            @Nullable ItemStack @NotNull [] snapshot,
            @NotNull Function<List<SlotDelta>, List<InventoryTransactions.Scope>> scoper
    ) {
    }

    /**
     * 纯读的规划上下文, simulate 等零副作用路径使用; 不触发任何根级写前钩子.
     */
    @NotNull
    PlanContext openPlan() {
        return this.capturePlan(false);
    }

    /**
     * 写路径的规划上下文: 在读取快照前对每个参与根触发一次 {@code beforePlan},
     * 让镜像型根完成线程校验与外部真相同步.
     */
    @NotNull
    PlanContext openPlanForWrite() {
        return this.capturePlan(true);
    }

    // 视图的通用规划: 逐槽解析, 同一根库存在整个事务中只读取一次快照引用,
    // 保证乐观校验基准与逻辑快照内部一致
    private PlanContext capturePlan(boolean forWrite) {
        int size = this.size();
        Map<AbstractInventory, @Nullable ItemStack[]> plannedByRoot = new LinkedHashMap<>();
        Anchor[] anchors = new Anchor[size];
        @Nullable ItemStack[] logical = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            Anchor anchor = this.resolveSlot(slot);
            anchors[slot] = anchor;
            @Nullable ItemStack[] planned = plannedByRoot.computeIfAbsent(anchor.root(), root -> {
                // 写前钩子先于快照读取: 镜像型根在此对账, 规划才基于最新真相
                if (forWrite) {
                    root.beforePlan();
                }
                return root.currentState();
            });
            logical[slot] = planned[anchor.rootSlot()];
        }
        return new PlanContext(logical, logicalDeltas -> toScopes(plannedByRoot, anchors, logicalDeltas));
    }

    // 把逻辑槽变更集按根库存分组并映射槽号, 产出跨根事务的参与范围
    private static List<InventoryTransactions.Scope> toScopes(
            Map<AbstractInventory, @Nullable ItemStack[]> plannedByRoot,
            Anchor[] anchors,
            List<SlotDelta> logicalDeltas
    ) {
        Map<AbstractInventory, List<SlotDelta>> deltasByRoot = new LinkedHashMap<>();
        for (int i = 0; i < logicalDeltas.size(); i++) {
            SlotDelta delta = logicalDeltas.get(i);
            Anchor anchor = anchors[delta.slot()];
            deltasByRoot.computeIfAbsent(anchor.root(), root -> new ArrayList<>()).add(delta.relocatedTo(anchor.rootSlot()));
        }

        List<InventoryTransactions.Scope> scopes = new ArrayList<>(deltasByRoot.size());
        for (Map.Entry<AbstractInventory, List<SlotDelta>> entry : deltasByRoot.entrySet()) {
            scopes.add(new InventoryTransactions.Scope(entry.getKey(), plannedByRoot.get(entry.getKey()), entry.getValue()));
        }
        return scopes;
    }

    private Subscription subscribeRoots(Function<AbstractInventory, Subscription> subscriber) {
        LinkedHashSet<AbstractInventory> roots = new LinkedHashSet<>();
        this.collectRoots(roots);
        Subscription[] subscriptions = new Subscription[roots.size()];
        int i = 0;
        for (AbstractInventory root : roots) {
            subscriptions[i++] = subscriber.apply(root);
        }
        return new CompositeSubscription(subscriptions);
    }

    /**
     * 聚合订阅凭证: 一次 close 关闭全部根库存上的转发订阅.
     */
    private static final class CompositeSubscription implements Subscription {
        private final Subscription[] subscriptions;

        private CompositeSubscription(Subscription[] subscriptions) {
            this.subscriptions = subscriptions;
        }

        @Override
        public boolean isClosed() {
            return this.subscriptions.length == 0 || this.subscriptions[0].isClosed();
        }

        @Override
        public void close() {
            for (int i = 0; i < this.subscriptions.length; i++) {
                this.subscriptions[i].close();
            }
        }
    }
}
