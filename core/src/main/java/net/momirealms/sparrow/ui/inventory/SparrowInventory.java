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
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryFactory;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * SparrowUI 所有受事务保护的Inventory的公共抽象, 可以把它理解成一个会自动通知变更的箱子.
 * <p>所有实现都遵守三条约定:
 * <ul>
 *   <li>空槽只用 {@code null} 表示, Inventory 中不会保留 AIR 物品或数量不大于 0 的物品;</li>
 *   <li>读出的物品都是快照, 修改返回值不会影响 Inventory;</li>
 *   <li>每次修改都走完整的规划、询问、提交和通知流程, 事件以整次修改为单位派发.</li>
 * </ul>
 * <p>读操作直接读取当前不可变快照, 任何线程都可以安全调用. 写操作遇到并发冲突时返回{@link TransactionResult.Conflicted} 且不产生修改;
 * <p>事务事件使用被订阅 Inventory 自己的槽位编号, 一笔事务对一个订阅最多通知一次.
 * <p>Window 交互事件则属于 被 InventoryLink 直接连接的逻辑 Inventory 实例, 不向根或外层视图传播.
 */
public abstract sealed class SparrowInventory permits RootInventory, ViewInventory {
    public static final int DEFAULT_MAX_STACK_SIZE = 99; // 槽位默认的堆叠上限
    static final TransactionResult.Committed EMPTY_COMMITTED = new TransactionResult.Committed(List.of()); // 无变更操作共享的成功结果: 变更列表为空, 也不派发事件

    // 三类操作各自挑选目标Inventory时用的优先级, 属于弱一致的配置; null 表示没有显式设置, 读取时回退到 fallbackGuiPriority.
    // 用 Integer 是为了允许 null 值可以被视为 "未设置".
    @Nullable private volatile Integer addGuiPriority;
    @Nullable private volatile Integer collectGuiPriority;
    @Nullable private volatile Integer otherGuiPriority;
    private final ObservableDispatcher<InventoryClickEvent> clickEvents = new ObservableDispatcher<>();
    private final ObservableDispatcher<InventoryBundleSelectEvent> bundleSelectEvents = new ObservableDispatcher<>();
    @Nullable private volatile InventoryTopology topology;             // 第一次读取槽位关系或订阅更新时创建
    @Nullable private volatile InventoryUpdateChannel updateChannel;   // 第一次订阅事务更新时创建
    // 懒加载的 Bukkit 包装实例, 同一 Inventory 恒为同一个实例.
    @Nullable private volatile org.bukkit.inventory.Inventory bukkitView;

    SparrowInventory() {
    }

    /**
     * 返回槽位数量, 创建后固定不变.
     *
     * @return 槽位数量
     */
    public abstract int size();

    /**
     * 一次性读出全部槽位, 得到当前时刻的独立副本.
     *
     * @return 按槽号排列的物品克隆数组, 空槽位置为 {@code null}
     */
    @Nullable
    public abstract ItemStack @NotNull [] snapshot();

    /**
     * 返回指定类别的批量操作按什么顺序遍历槽位.
     *
     * @param category 操作类别
     * @return 该类别使用的遍历顺序
     */
    @NotNull
    public abstract SlotOrder iterationOrder(@NotNull OperationCategory category);

    /**
     * 把 Window 的逻辑槽号换算成 RootInventory 里的槽位;
     *
     * @param slot 逻辑槽号
     * @return 该逻辑槽落到的  RootInventory 槽位
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
     * 返回指定类别的操作挑选目标 Inventory 时使用的优先级, 数值越大越优先.
     *
     * @param category 操作类别
     * @return 该类别的优先级
     */
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
     *
     * @param category 操作类别
     * @return 该类别的回退优先级
     */
    int fallbackGuiPriority(@NotNull OperationCategory category) {
        return 0;
    }

    /**
     * 设置指定类别的操作挑选目标 Inventory 时使用的优先级, 越大越先被选中.
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
     * 清除指定类别显式设置的优先级, 让它回到回退值.
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
     * 读取指定槽位的物品, 空槽返回 {@code null}.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 槽内物品的克隆, 空槽为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    public abstract ItemStack itemAt(int slot);

    /**
     * 指定槽位自身的堆叠上限, 不含物品自带的堆叠上限.
     * 放入物品时真正生效的上限是两者的较小值.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 该槽位的堆叠上限
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public abstract int slotMaxStackSize(int slot);

    /**
     * 每个槽位是否都装有达到有效堆叠上限的物品.
     *
     * @return 全部槽位都满时返回 {@code true}
     */
    public boolean isFull() {
        ItemStack[] snapshot = this.snapshot();
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack item = snapshot[i];
            if (item == null || item.getAmount() < Math.min(this.slotMaxStackSize(i), item.getMaxStackSize())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断 Inventory 是否没有任何物品.
     *
     * @return 全部槽位都为空时返回 {@code true}
     */
    public boolean isEmpty() {
        ItemStack[] snapshot = this.snapshot();
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断 Inventory 是否至少有一个空槽.
     *
     * @return 存在空槽时返回 {@code true}
     */
    public boolean hasEmptySlot() {
        ItemStack[] snapshot = this.snapshot();
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否存在 matcher 选中的物品.
     *
     * @param matcher 判断物品是否符合条件的函数
     * @return 至少有一个物品符合条件时返回 {@code true}
     */
    public boolean contains(@NotNull Predicate<? super ItemStack> matcher) {
        ItemStack[] snapshot = this.snapshot();
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack item = snapshot[i];
            if (item != null && matcher.test(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否存在与 template 相似的物品堆, 不比较数量.
     *
     * @param template 用于相似判断的物品样板
     * @return 至少有一个相似物品堆时返回 {@code true}
     */
    public boolean containsSimilar(@NotNull ItemStack template) {
        return this.contains(item -> item.isSimilar(template));
    }

    /**
     * 统计 matcher 选中的物品堆数量, 不累加堆内物品数量.
     *
     * @param matcher 判断物品是否符合条件的函数
     * @return 符合条件的非空槽数量
     */
    public int count(@NotNull Predicate<? super ItemStack> matcher) {
        ItemStack[] snapshot = this.snapshot();
        int count = 0;
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack item = snapshot[i];
            if (item != null && matcher.test(item)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计与 template 相似的物品堆数量, 不比较也不累加数量.
     *
     * @param template 用于相似判断的物品样板
     * @return 相似物品堆所在的非空槽数量
     */
    public int countSimilar(@NotNull ItemStack template) {
        return this.count(item -> item.isSimilar(template));
    }

    /**
     * 判断指定槽位是否装有物品.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 槽位非空时返回 {@code true}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public boolean hasItem(int slot) {
        return this.itemAt(slot) != null;
    }

    /**
     * 返回指定槽位内的物品数量, 空槽返回 0.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 槽内物品数量, 空槽为 0
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public int itemAmount(int slot) {
        ItemStack item = this.itemAt(slot);
        return item == null ? 0 : item.getAmount();
    }

    /**
     * 权威写入单个槽位, 覆盖为给定物品, {@code null} 表示清空.
     * 即使新值与当前值相等也会产生事务与事件.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public abstract TransactionResult setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item);

    /**
     * 与 {@link #setItem} 相同, 但跳过 pre 事件且无法被取消;
     * post 事件仍会正常派发.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public abstract TransactionResult forceSetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item);

    /**
     * 往指定槽位尽量放入物品.
     * 空槽会直接放入, 相似物品会合并, 不相似时全部数量都会剩余.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public abstract AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item);

    /**
     * 读、改、写指定槽位.
     * modifier 接收当前物品的克隆, 返回 {@code null} 表示清空.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param modifier 接收旧物品克隆并返回新物品的函数
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public abstract TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier);

    /**
     * 增减槽内物品数量. 减少时最低到 0, 增加时最高到有效堆叠上限;
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param change 数量变化, 正数为增加, 负数为减少
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public abstract TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change);

    /**
     * 按 ADD 遍历顺序把物品尽量放进 Inventory , 先合并相似物品堆, 再占用空槽.
     * 整个放入过程作为一次事务提交.
     *
     * @param reason 本次修改的原因
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     */
    @NotNull
    public AddResult add(@NotNull UpdateReason reason, @NotNull ItemStack item) {
        // 先克隆再判空: "是不是空物品" 的结论和后续读取永远一致
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        PlanContext context = this.openPlanForWrite();
        // 在逻辑快照上规划: 先合并相似的未满堆, 再占空槽
        InventoryPlanner.AddPlan plan = InventoryPlanner.planAdd(
                context.snapshot(),
                input,
                this.iterationOrder(OperationCategory.ADD),
                this::slotMaxStackSize
        );
        if (plan.deltas().isEmpty()) {
            return new AddResult(EMPTY_COMMITTED, plan.remaining());
        }
        // 把逻辑槽变更按  RootInventory 分组后整体提交; 没提交成功视为一个都没放进去
        TransactionResult result = InventoryTransactions.commit(reason, context.scoper().apply(plan.deltas()), false);
        return new AddResult(result, result instanceof TransactionResult.Committed ? plan.remaining() : input.getAmount());
    }

    /**
     * 按 COLLECT 遍历顺序收集与 template 相似的物品, 最多收集 {@code upTo} 个.
     * 整个收集过程作为一次事务提交.
     *
     * @param reason 本次修改的原因
     * @param template 物品样板, 只参与相似判断
     * @param upTo 最多收集的数量
     * @return 收集结果, 包含实际收集数量
     */
    @NotNull
    public CollectResult collect(@NotNull UpdateReason reason, @NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(template));
        if (sample == null || upTo <= 0) {
            return new CollectResult(EMPTY_COMMITTED, 0);
        }
        PlanContext context = this.openPlanForWrite();
        // 在逻辑快照上规划: 先收未满堆, 不够再收满堆
        InventoryPlanner.TakePlan plan = InventoryPlanner.planCollect(
                context.snapshot(),
                sample,
                upTo,
                this.iterationOrder(OperationCategory.COLLECT),
                null,
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
     * 按 OTHER 遍历顺序移除 matcher 选中的物品, 最多移除 {@code upTo} 个.
     * 整个移除过程作为一次事务提交.
     *
     * @param reason 本次修改的原因
     * @param matcher 判断某个物品是否应被移除的函数
     * @param upTo 最多移除的数量
     * @return 移除结果, 包含实际移除数量
     */
    @NotNull
    public RemoveResult remove(@NotNull UpdateReason reason, @NotNull Predicate<@NotNull ItemStack> matcher, int upTo) {
        if (upTo <= 0) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        PlanContext context = this.openPlanForWrite();
        // 在逻辑快照上规划要动哪些槽; matcher 由规划器在锁外逐个调用
        InventoryPlanner.TakePlan plan = InventoryPlanner.planRemove(context.snapshot(), matcher, upTo, this.iterationOrder(OperationCategory.OTHER));
        if (plan.deltas().isEmpty()) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        // 按根分组整体提交; 没提交成功视为一个都没移除
        TransactionResult result = InventoryTransactions.commit(reason, context.scoper().apply(plan.deltas()), false);
        return new RemoveResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    /**
     * 试算现在放入给定物品后会有多少数量剩余.
     *
     * @param item 要试算的物品
     * @return 预计放不下的数量
     */
    public int simulateAdd(@NotNull ItemStack item) {
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return 0;
        }
        return InventoryPlanner.planAdd(this.openPlan().snapshot(), input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize).remaining();
    }

    /**
     * 在规划快照上按参数顺序连续试算放入多个物品.
     *
     * @param items 要试算的物品
     * @return 与参数顺序一致的剩余数量数组
     */
    public int[] simulateAdd(ItemStack @NotNull ... items) {
        return this.simulateAdd(Arrays.asList(items));
    }

    /**
     * 在规划快照上按列表顺序连续试算放入多个物品.
     *
     * @param items 要试算的物品
     * @return 与列表顺序一致的剩余数量数组
     */
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
     * 试算现在收集与 template 相似的物品时能收集多少数量.
     *
     * @param template 物品样板, 只参与相似判断
     * @param upTo 最多收集的数量
     * @return 预计能收集到的数量
     */
    public int simulateCollect(@NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(template));
        if (sample == null || upTo <= 0) {
            return 0;
        }
        return InventoryPlanner.planCollect(this.openPlan().snapshot(), sample, upTo, this.iterationOrder(OperationCategory.COLLECT), null, this::slotMaxStackSize).taken();
    }

    /**
     * 判断 Inventory 能否完整装下给定物品.
     *
     * @param item 要检查的物品
     * @return 能完整装下时返回 {@code true}
     */
    public boolean canHold(@NotNull ItemStack item) {
        return this.simulateAdd(item) == 0;
    }

    /**
     * 判断 Inventory 能否按参数顺序完整装下全部物品.
     *
     * @param items 要检查的物品
     * @return 全部能装下时返回 {@code true}
     */
    public boolean canHold(ItemStack @NotNull ... items) {
        int[] remaining = this.simulateAdd(items);
        for (int i = 0; i < remaining.length; i++) {
            if (remaining[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断 Inventory 能否按列表顺序完整装下全部物品.
     *
     * @param items 要检查的物品
     * @return 全部能装下时返回 {@code true}
     */
    public boolean canHold(@NotNull List<? extends ItemStack> items) {
        int[] remaining = this.simulateAdd(items);
        for (int i = 0; i < remaining.length; i++) {
            if (remaining[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 让 ReferencingInventory 同步最新内容.
     * 自己持有数据的 RootInventory 调用它没有效果;
     * 视图会把调用转发给背后的全部 RootInventory .
     * ReferencingInventory 的调用方必须保证当前线程可以访问外部容器;
     * 平台拒绝访问时异常会直接传播.
     */
    public abstract void refresh();

    /**
     * 把 SparrowInventory 包装成原生 CraftInventory, 同一个 Inventory 永远返回同一个包装实例.
     * CraftInventory 背后的 NMS Container 直接代理本 Inventory, 槽位写入会走 Sparrow 的事务流程.
     * 与真实容器绑定的信息(观看者, 持有者, 位置) 一律为 "Null", 类型固定为 CHEST.
     *
     * @return CraftInventory
    */
    @NotNull
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
     * 订阅玩家点击本 Inventory 连接槽的事件.
     * 事件在事务规划前派发, 取消会阻止本次点击进入规划与提交.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    public Subscription subscribeClick(@NotNull Observer<? super InventoryClickEvent> observer) {
        return this.clickEvents.subscribe(observer);
    }

    /**
     * 订阅玩家在本 Inventory 连接槽中的 Bundle 选择事件.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    public Subscription subscribeBundleSelect(@NotNull Observer<? super InventoryBundleSelectEvent> observer) {
        return this.bundleSelectEvents.subscribe(observer);
    }

    /**
     * 向当前逻辑 Inventory 的观察者派发一次点击.
     */
    void publishClick(@NotNull InventoryClickEvent event) {
        this.clickEvents.publish(event);
    }

    /**
     * 向当前逻辑 Inventory 的观察者派发一次 Bundle 选择.
     */
    void publishBundleSelect(@NotNull InventoryBundleSelectEvent event) {
        this.bundleSelectEvents.publish(event);
    }

    /**
     * 订阅事务提交前的事件, 处理器可以取消整个事务.
     * 一笔事务对本次订阅最多通知一次. {@link InventoryPreUpdateEvent#deltas()} 中的槽位编号属于当前 Inventory,
     * 当前 Inventory 没有可见变化时不会通知.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    public Subscription subscribePreUpdate(@NotNull Observer<? super InventoryPreUpdateEvent> observer) {
        return this.updateChannel().subscribePre(observer);
    }

    /**
     * 订阅事务提交后的事件. 一笔事务对本次订阅最多通知一次.
     * {@link InventoryPostUpdateEvent#deltas()} 中的槽位编号属于当前 Inventory, 没有可见变化时不会通知.
     * 连续修改同一个 RootInventory 时, 事件顺序与事务提交顺序一致.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    public Subscription subscribePostUpdate(@NotNull Observer<? super InventoryPostUpdateEvent> observer) {
        return this.updateChannel().subscribePost(observer);
    }

    /**
     * 打开纯读用途的规划上下文: 给 simulate 这类零副作用的路径使用;
     *
     * @return 规划上下文
     */
    @NotNull
    abstract PlanContext openPlan();

    /**
     * 打开写路径的规划上下文: 在读快照之前, 先让每个参与的 RootInventory 做一次写前准备.
     * (ReferencingInventory 在这个方法完成外部内容同步).
     *
     * @return 规划上下文
     */
    @NotNull
    abstract PlanContext openPlanForWrite();

    /**
     * 返回当前 Inventory 与 RootInventory 之间的槽位关系, 第一次调用时计算.
     *
     * @return 当前 Inventory 的槽位映射
     */
    @NotNull
    final InventoryTopology topology() {
        InventoryTopology topology = this.topology;
        if (topology == null) {
            synchronized (this) {
                // 再检查一次, 避免两个线程同时计算相同的槽位关系.
                topology = this.topology;
                if (topology == null) {
                    topology = InventoryTopology.compile(this);
                    this.topology = topology;
                }
            }
        }
        return topology;
    }

    /**
     * 返回负责保存当前 Inventory 订阅并发送更新事件的对象, 第一次订阅时创建.
     *
     * @return 当前 Inventory 用来发送更新事件的对象
     */
    @NotNull
    private InventoryUpdateChannel updateChannel() {
        InventoryUpdateChannel channel = this.updateChannel;
        if (channel == null) {
            synchronized (this) {
                channel = this.updateChannel;
                if (channel == null) {
                    channel = new InventoryUpdateChannel(this.topology());
                    this.updateChannel = channel;
                }
            }
        }
        return channel;
    }

    /**
     * 一次批量规划的上下文, 逻辑槽快照 + 把逻辑槽变更换算成各 RootInventory 事务范围的函数.
     *
     * @param snapshot 规划用的逻辑槽快照, 空槽位置为 {@code null}
     * @param scoper 把逻辑槽变更集拆成各  RootInventory 事务范围的函数
     */
    record PlanContext(
            @Nullable ItemStack @NotNull [] snapshot,
            @NotNull Function<List<SlotDelta>, List<InventoryTransactions.Scope>> scoper
    ) {
    }

}
