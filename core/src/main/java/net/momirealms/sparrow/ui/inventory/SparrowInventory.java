package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.inventory.event.InventoryBundleSelectEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryClickEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.CollectResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryFactory;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 所有内建Inventory的共同基类: 把"第几个槽"换算到真正存数据的根Inventory槽位.
 * <p>Inventory分两种角色: 自己持有数据的根Inventory({@link AbstractInventory} 家族), 和不持有数据,
 * 只把读写转发给根Inventory的视图(拼接, 遮蔽等). 视图的每个逻辑槽最终都落在某个根Inventory的某个槽上,
 * 这个换算由 {@link #resolveSlot(int)} 完成; 批量操作先在逻辑槽的快照上规划好, 再按根Inventory分组,
 * 由 {@link InventoryTransactions} 一次性提交 —— 跨多个根Inventory也能保证要么全部生效, 要么全部不生效.
 * <p>事务事件由根Inventory产生: 在视图上订阅会转发到背后的全部根. Window交互事件则属于
 * 被InventoryLink直接连接的逻辑Inventory实例, 不向根或外层视图传播.
 */
abstract class SparrowInventory implements Inventory {
    static final TransactionResult.Committed EMPTY_COMMITTED = new TransactionResult.Committed(List.of()); // 无变更操作共享的成功结果: 变更列表为空, 也不派发事件

    // 三类操作各自挑选目标Inventory时用的优先级, 属于弱一致的配置; null 表示没有显式设置, 读取时回退到 fallbackGuiPriority.
    // 用 Integer 是为了允许 null 值可以被视为 "未设置".
    @Nullable private volatile Integer addGuiPriority;
    @Nullable private volatile Integer collectGuiPriority;
    @Nullable private volatile Integer otherGuiPriority;
    private final ObservableDispatcher<InventoryClickEvent> clickEvents = new ObservableDispatcher<>();
    private final ObservableDispatcher<InventoryBundleSelectEvent> bundleSelectEvents = new ObservableDispatcher<>();
    // 懒加载的 Bukkit 包装实例: Bukkit 侧靠引用相等辨认Inventory, 所以同一Inventory必须恒为同一个实例
    @Nullable private volatile org.bukkit.inventory.Inventory bukkitView;

    /**
     * 把 Window 的逻辑槽号换算成根 Inventory 里的槽位;
     * 自己持有数据的实现直接返回自身.
     *
     * @param slot 逻辑槽号
     * @return 该逻辑槽落到的根Inventory槽位
     */
    @NotNull
    abstract SlotKey.Anchor resolveSlot(int slot);

    /**
     * 把 Window 的逻辑槽号一路换算到最终的真实存储槽.
     *
     * @param slot 逻辑槽号
     * @return 该槽的最终物理身份
     */
    @NotNull
    final SlotKey physicalKey(int slot) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().rootPhysicalKey(anchor);
    }

    /**
     * 按遍历顺序收集本Inventory背后全部去重后的根Inventory,
     * 服务于订阅转发与跨根Inventory事务展开.
     *
     * @param roots 接收结果的集合, 根Inventory按遍历顺序放入
     */
    abstract void collectRoots(@NotNull LinkedHashSet<AbstractInventory> roots);

    /**
     * {@inheritDoc}
     *
     * <p>显式设置过的类别返回设置值; 没设置的类别返回 {@link #fallbackGuiPriority} 的回退值.
     */
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
     * 没有显式设置优先级时使用的回退值.
     * <p>自己持有数据的Inventory没有可委托的对象, 回退为 0;
     * 装饰视图覆写此方法改为透传底层Inventory的取值, 从而得到"没设置就跟着底层走, 设置了就盖住底层"的效果.
     *
     * @param category 操作类别
     * @return 该类别的回退优先级
     */
    int fallbackGuiPriority(@NotNull OperationCategory category) {
        return 0;
    }

    /**
     * 设置指定类别的操作挑选目标Inventory时使用的优先级, 越大越先被选中.
     *
     * @param category 操作类别
     * @param priority 优先级, 越大越优先
     */
    public void guiPriority(@NotNull OperationCategory category, int priority) {
        switch (category) {
            case ADD -> this.addGuiPriority = priority;
            case COLLECT -> this.collectGuiPriority = priority;
            case OTHER -> this.otherGuiPriority = priority;
        }
    }

    /**
     * 一次设置全部三个类别的优先级.
     *
     * @param priority 优先级, 越大越优先
     */
    public void guiPriority(int priority) {
        this.addGuiPriority = priority;
        this.collectGuiPriority = priority;
        this.otherGuiPriority = priority;
    }

    /**
     * 清除指定类别显式设置的优先级, 让它回到回退值:
     * 自持数据的Inventory回到 0, 装饰视图重新跟随底层Inventory.
     *
     * @param category 操作类别
     */
    public void clearGuiPriority(@NotNull OperationCategory category) {
        switch (category) {
            case ADD -> this.addGuiPriority = null;
            case COLLECT -> this.collectGuiPriority = null;
            case OTHER -> this.otherGuiPriority = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public ItemStack itemAt(int slot) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().itemAt(anchor.rootSlot());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int slotMaxStackSize(int slot) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().slotMaxStackSize(anchor.rootSlot());
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public TransactionResult setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().setItem(reason, anchor.rootSlot(), item);
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public TransactionResult forceSetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().forceSetItem(reason, anchor.rootSlot(), item);
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().putItem(reason, anchor.rootSlot(), item);
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().modifyItem(reason, anchor.rootSlot(), modifier);
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().changeAmount(reason, anchor.rootSlot(), change);
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现先在逻辑槽的快照上算出完整的放置方案, 再作为一次事务整体提交.
     */
    @NotNull
    @Override
    public AddResult add(@NotNull UpdateReason reason, @NotNull ItemStack item) {
        // 先克隆再判空: "是不是空物品" 的结论和后续读取永远一致
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        PlanContext context = this.openPlanForWrite();
        // 一个可写的根都没有(比如ReferencingInventory当前线程访问不了目标), 整体不可用
        if (!context.anyWritable()) {
            return new AddResult(TransactionResult.Unavailable.INSTANCE, input.getAmount());
        }
        // 在逻辑快照上规划: 先合并相似的未满堆, 再占空槽; 不可写的槽上限按 0 算
        InventoryPlanner.AddPlan plan = InventoryPlanner.planAdd(
                context.snapshot(),
                input,
                this.iterationOrder(OperationCategory.ADD),
                slot -> context.writable(slot) ? this.slotMaxStackSize(slot) : 0
        );
        if (plan.deltas().isEmpty()) {
            return new AddResult(EMPTY_COMMITTED, plan.remaining());
        }
        // 把逻辑槽变更按根Inventory分组后整体提交; 没提交成功视为一个都没放进去
        TransactionResult result = InventoryTransactions.commit(reason, context.scoper().apply(plan.deltas()), false);
        return new AddResult(result, result instanceof TransactionResult.Committed ? plan.remaining() : input.getAmount());
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现先在逻辑槽的快照上算出完整的收集方案, 再作为一次事务整体提交.
     */
    @NotNull
    @Override
    public CollectResult collect(@NotNull UpdateReason reason, @NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(template));
        if (sample == null || upTo <= 0) {
            return new CollectResult(EMPTY_COMMITTED, 0);
        }
        PlanContext context = this.openPlanForWrite();
        // 一个可写的根都没有, 整体不可用
        if (!context.anyWritable()) {
            return new CollectResult(TransactionResult.Unavailable.INSTANCE, 0);
        }
        // 在逻辑快照上规划: 先收未满堆, 不够再收满堆; 不可写的槽跳过
        InventoryPlanner.TakePlan plan = InventoryPlanner.planCollect(
                context.snapshot(),
                sample,
                upTo,
                this.iterationOrder(OperationCategory.COLLECT),
                context::writable,
                this::slotMaxStackSize
        );
        if (plan.deltas().isEmpty()) {
            return new CollectResult(EMPTY_COMMITTED, 0);
        }
        // 按根分组整体提交; 没提交成功视为一个都没收到
        TransactionResult result = InventoryTransactions.commit(reason, context.scoper().apply(plan.deltas()), false);
        return new CollectResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现先在逻辑槽的快照上算出完整的移除方案, 再作为一次事务整体提交.
     */
    @NotNull
    @Override
    public RemoveResult remove(@NotNull UpdateReason reason, @NotNull Predicate<@NotNull ItemStack> matcher, int upTo) {
        if (upTo <= 0) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        PlanContext context = this.openPlanForWrite();
        // 一个可写的根都没有, 整体不可用
        if (!context.anyWritable()) {
            return new RemoveResult(TransactionResult.Unavailable.INSTANCE, 0);
        }
        // 在逻辑快照上规划要动哪些槽; matcher 由规划器在锁外逐个调用
        InventoryPlanner.TakePlan plan = InventoryPlanner.planRemove(
                context.snapshot(),
                matcher,
                upTo,
                this.iterationOrder(OperationCategory.OTHER),
                context::writable
        );
        if (plan.deltas().isEmpty()) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        // 按根分组整体提交; 没提交成功视为一个都没移除
        TransactionResult result = InventoryTransactions.commit(reason, context.scoper().apply(plan.deltas()), false);
        return new RemoveResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int simulateAdd(@NotNull ItemStack item) {
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return 0;
        }
        return InventoryPlanner.planAdd(this.openPlan().snapshot(), input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize).remaining();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int[] simulateAdd(@NotNull List<? extends ItemStack> items) {
        @Nullable ItemStack[] working = this.openPlan().snapshot().clone();
        int[] remaining = new int[items.size()];
        int index = 0;
        for (ItemStack item : items) {
            @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
            if (input == null) {
                index++;
                continue;
            }
            InventoryPlanner.AddPlan plan = InventoryPlanner.planAdd(working, input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize);
            remaining[index] = plan.remaining();
            List<SlotDelta> deltas = plan.deltas();
            for (int j = 0; j < deltas.size(); j++) {
                SlotDelta delta = deltas.get(j);
                working[delta.slot()] = delta.after();
            }
            index++;
        }
        return remaining;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int simulateCollect(@NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(template));
        if (sample == null || upTo <= 0) {
            return 0;
        }
        return InventoryPlanner.planCollect(this.openPlan().snapshot(), sample, upTo, this.iterationOrder(OperationCategory.COLLECT), null, this::slotMaxStackSize).taken();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canHold(@NotNull ItemStack item) {
        return this.simulateAdd(item) == 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现把调用逐根转发下去;
     * 真正和外部容器同步的工作由 ReferencingInventory 的覆写完成.
     */
    @Override
    public void refresh() {
        LinkedHashSet<AbstractInventory> roots = new LinkedHashSet<>();
        this.collectRoots(roots);
        for (AbstractInventory root : roots) {
            root.refresh();
        }
    }

    /**
     * 把 SparrowInventory 包装成原生 CraftInventory, 同一个 Inventory 永远返回同一个包装实例.
     * CraftInventory 背后的 NMS Container 直接代理本 Inventory, 槽位写入会走 Sparrow 的事务流程.
     * 与真实容器绑定的信息(观看者, 持有者, 位置) 一律为 "Null", 类型固定为 CHEST.
     *
     * @return CraftInventory
     */
    @NotNull
    @Override
    @ApiStatus.Experimental
    public org.bukkit.inventory.Inventory asBukkitInventory() {
        // 双重检查锁定: Bukkit 以 == 或 Map 键辨认 Inventory, 每次新建实例都会破坏身份语义
        org.bukkit.inventory.Inventory view = this.bukkitView;
        if (view == null) {
            synchronized (this) {
                view = this.bukkitView;
                if (view == null) {
                    view = CraftInventoryFactory.create(new InventoryContainerHandler(this));
                    this.bukkitView = view;
                }
            }
        }
        return view;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public Subscription subscribeClick(@NotNull Observer<? super InventoryClickEvent> observer) {
        return this.clickEvents.subscribe(observer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public Subscription subscribeBundleSelect(@NotNull Observer<? super InventoryBundleSelectEvent> observer) {
        return this.bundleSelectEvents.subscribe(observer);
    }

    /**
     * 向当前逻辑Inventory的观察者派发一次点击.
     */
    void publishClick(@NotNull InventoryClickEvent event) {
        this.clickEvents.publish(event);
    }

    /**
     * 向当前逻辑Inventory的观察者派发一次Bundle选择.
     */
    void publishBundleSelect(@NotNull InventoryBundleSelectEvent event) {
        this.bundleSelectEvents.publish(event);
    }

    /**
     * {@inheritDoc}
     *
     * <p>订阅会被转发到背后的全部根Inventory, 任一根上的事务都会通知 observer.
     */
    @Override
    @NotNull
    public Subscription subscribePreUpdate(@NotNull Observer<? super InventoryPreUpdateEvent> observer) {
        return this.subscribeRoots(root -> root.subscribePreUpdate(observer));
    }

    /**
     * {@inheritDoc}
     *
     * <p>订阅会被转发到背后的全部根Inventory, 任一根上的事务都会通知 observer.
     */
    @Override
    @NotNull
    public Subscription subscribePostUpdate(@NotNull Observer<? super InventoryPostUpdateEvent> observer) {
        return this.subscribeRoots(root -> root.subscribePostUpdate(observer));
    }

    /**
     * 打开纯读用途的规划上下文: 给 simulate 这类零副作用的路径使用;
     * 不触发任何根Inventory的写前准备.
     *
     * @return 规划上下文
     */
    @NotNull
    PlanContext openPlan() {
        return this.capturePlan(false);
    }

    /**
     * 打开写路径的规划上下文: 在读快照之前, 先让每个参与的根Inventory做一次写前准备.
     * (ReferencingInventory 在这个方法完成线程校验和外部内容同步).
     *
     * @return 规划上下文
     */
    @NotNull
    PlanContext openPlanForWrite() {
        return this.capturePlan(true);
    }

    /**
     * 为视图采集规划上下文: 逐槽解析到根Inventory并读取快照. 同一个根Inventory在整个事务中
     * 只读取一次快照引用, 保证之后提交时的校验基准和逻辑快照内部一致.
     *
     * @param forWrite 是否用于写路径; 写路径会先触发各根的写前准备
     * @return 规划上下文
     */
    private PlanContext capturePlan(boolean forWrite) {
        int size = this.size();
        Map<AbstractInventory, @Nullable ItemStack[]> plannedByRoot = new LinkedHashMap<>();
        Map<AbstractInventory, Boolean> writableByRoot = new LinkedHashMap<>();
        SlotKey.Anchor[] anchors = new SlotKey.Anchor[size];
        @Nullable ItemStack[] logical = new ItemStack[size];
        // 逐槽解析: 每个根Inventory只在首次遇到时做一次写前准备并读一次快照
        for (int slot = 0; slot < size; slot++) {
            SlotKey.Anchor anchor = this.resolveSlot(slot);
            anchors[slot] = anchor;
            @Nullable ItemStack[] planned = plannedByRoot.computeIfAbsent(anchor.root(), root -> {
                // 写前准备先于读取快照: 镜像型根在这里同步外部内容, 规划才基于最新数据
                writableByRoot.put(root, !forWrite || root.prepareWrite());
                return root.currentState();
            });
            logical[slot] = planned[anchor.rootSlot()];
        }
        return new PlanContext(
                logical,
                logicalDeltas -> toScopes(plannedByRoot, anchors, logicalDeltas),
                slot -> writableByRoot.get(anchors[slot].root())
        );
    }

    /**
     * 把逻辑槽的变更集按根Inventory分组, 并把槽号换算成根Inventory里的槽号, 产出跨根事务的参与范围.
     *
     * @param plannedByRoot 各根Inventory在规划时读取的快照
     * @param anchors 每个逻辑槽对应的根Inventory槽位
     * @param logicalDeltas 逻辑槽变更集
     * @return 按根Inventory分组的事务参与范围
     */
    private static List<InventoryTransactions.Scope> toScopes(
            Map<AbstractInventory, @Nullable ItemStack[]> plannedByRoot,
            SlotKey.Anchor[] anchors,
            List<SlotDelta> logicalDeltas
    ) {
        // 按根分组, 同时把槽号换算成根Inventory内的槽号
        Map<AbstractInventory, List<SlotDelta>> deltasByRoot = new LinkedHashMap<>();
        for (int i = 0; i < logicalDeltas.size(); i++) {
            SlotDelta delta = logicalDeltas.get(i);
            SlotKey.Anchor anchor = anchors[delta.slot()];
            deltasByRoot.computeIfAbsent(anchor.root(), root -> new ArrayList<>()).add(delta.relocatedTo(anchor.rootSlot()));
        }

        // 每个有变更的根Inventory产出一个事务参与范围
        List<InventoryTransactions.Scope> scopes = new ArrayList<>(deltasByRoot.size());
        for (Map.Entry<AbstractInventory, List<SlotDelta>> entry : deltasByRoot.entrySet()) {
            scopes.add(new InventoryTransactions.Scope(entry.getKey(), plannedByRoot.get(entry.getKey()), entry.getValue()));
        }
        return scopes;
    }

    /**
     * 把同一个订阅动作应用到所有根Inventory上, 聚合成一个凭证返回.
     * 中途失败时会把已建立的订阅逆序关掉, 保证全有或全无.
     *
     * @param subscriber 对单个根Inventory执行订阅的动作
     * @return 聚合后的订阅凭证
     */
    private Subscription subscribeRoots(Function<AbstractInventory, Subscription> subscriber) {
        LinkedHashSet<AbstractInventory> roots = new LinkedHashSet<>();
        this.collectRoots(roots);
        Subscription[] subscriptions = new Subscription[roots.size()];
        int subscribed = 0;
        try {
            for (AbstractInventory root : roots) {
                subscriptions[subscribed] = subscriber.apply(root);
                subscribed++;
            }
        } catch (RuntimeException | Error throwable) {
            // 全有或全无: 中途失败时逆序关闭已建立的根订阅, 调用方无从关闭未返回的凭证
            for (int i = subscribed - 1; i >= 0; i--) {
                ThrowableUtils.captureUnchecked(throwable, subscriptions[i]::close);
            }
            throw throwable;
        }
        return new CompositeSubscription(subscriptions);
    }

    /**
     * 一次批量规划的上下文: 一张逻辑槽快照, 加上把逻辑槽变更换算成各根Inventory事务范围的函数.
     * 自持数据的Inventory直接用自己的状态; 视图则把每个逻辑槽逐一解析到根Inventory并按根分组.
     *
     * @param snapshot 规划用的逻辑槽快照, 空槽位置为 {@code null}
     * @param scoper 把逻辑槽变更集拆成各根Inventory事务范围的函数
     * @param writable 判断某个逻辑槽当前是否可写
     */
    record PlanContext(
            @Nullable ItemStack @NotNull [] snapshot,
            @NotNull Function<List<SlotDelta>, List<InventoryTransactions.Scope>> scoper,
            @NotNull IntPredicate writable
    ) {

        /**
         * 判断指定逻辑槽当前是否可写.
         *
         * @param slot 逻辑槽号
         * @return 可写返回 {@code true}
         */
        boolean writable(int slot) {
            return this.writable.test(slot);
        }

        /**
         * 判断是否至少有一个逻辑槽可写;
         * 空快照视为全部可写.
         *
         * @return 存在可写槽位返回 {@code true}
         */
        boolean anyWritable() {
            if (this.snapshot.length == 0) {
                return true;
            }
            for (int slot = 0; slot < this.snapshot.length; slot++) {
                if (this.writable(slot)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 聚合多个订阅的凭证: 一次 {@link #close()} 关掉全部根Inventory上的转发订阅.
     */
    private static final class CompositeSubscription implements Subscription {
        private final Subscription[] subscriptions; // 各根Inventory上的订阅凭证

        /**
         * 包装一组已建立的订阅.
         *
         * @param subscriptions 各根Inventory上的订阅凭证
         */
        private CompositeSubscription(Subscription[] subscriptions) {
            this.subscriptions = subscriptions;
        }

        /**
         * 判断订阅是否已关闭, 以第一个子订阅的状态为代表; 空订阅组视为已关闭.
         *
         * @return 已关闭返回 {@code true}
         */
        @Override
        public boolean isClosed() {
            return this.subscriptions.length == 0 || this.subscriptions[0].isClosed();
        }

        /**
         * {@inheritDoc}
         *
         * <p>逐个关闭所有子订阅.
         */
        @Override
        public void close() {
            for (int i = 0; i < this.subscriptions.length; i++) {
                this.subscriptions[i].close();
            }
        }
    }
}
