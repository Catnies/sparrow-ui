package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.inventory.event.InventoryBundleSelectEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.SparrowInventoryClickEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.CollectResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.inventory.storage.ExternalStorage;
import net.momirealms.sparrow.ui.inventory.storage.SlotKey;
import net.momirealms.sparrow.ui.inventory.transaction.InventoryTransactions;
import net.momirealms.sparrow.ui.inventory.transaction.InventoryUpdateChannel;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryFactory;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.visual.InventoryVisual;
import net.momirealms.sparrow.ui.visual.InventoryVisualImpl;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 受事务保护并可订阅内容变更的 Inventory 基类, 空槽使用 {@code null}.
 * <p>普通读取返回副本. 名称以 {@code unsafe} 开头的入口会暴露内部对象, <strong>只读, 不得修改或持有</strong>.
 * 内容相等的写入仍可产生事务和事件, 实现也可以保留原物品实例.
 */
public abstract class SparrowInventory {
    public static final int DEFAULT_MAX_STACK_SIZE = 99; // 槽位默认的堆叠上限
    private static final TransactionResult.Committed EMPTY_COMMITTED = new TransactionResult.Committed(List.of()); // 无变更操作共享的成功结果, 变更列表为空, 也不派发事件
    private static final AtomicLong LOCK_ORDER_SOURCE = new AtomicLong(); // 锁序号发号器, 每创建一个 Inventory 发一个号

    // 事务身份与固定协作组件
    private final long lockOrder = LOCK_ORDER_SOURCE.getAndIncrement(); // 跨 Inventory 事务按这个序号决定加锁先后
    private final ReentrantLock writeLock = new ReentrantLock();        // 只用来串行化写操作, 临界区内全是纯内存操作
    private final SlotOrder naturalOrder;                               // 遍历顺序的缺省回退, 构造时按槽位数建一次
    private final Bindings bindings = new Bindings();                   // 本 Inventory 持有的 Signal 绑定
    private final InventoryVisualImpl visual;                           // 视觉配置, Signal 绑定与逐槽显示路径失效订阅
    // 内容状态与放入规则
    @Nullable private volatile ItemStack @NotNull [] state; // 当前内部状态版本, 数组和物品均归 Inventory 内部所有
    @Nullable private volatile Predicate<ItemStack> placementRule; // 容器全局物品放入规则, null 表示放行
    @Nullable private volatile Predicate<ItemStack> @NotNull [] placementRulesBySlot; // 容器槽位的物品放入规则, 非 null 时覆盖全局规则
    // 玩家操作配置
    private volatile int addOperationPriority;
    private volatile int collectOperationPriority;
    private volatile int otherOperationPriority;
    private volatile boolean includeObscuredSlots; // 未被 Pane 展示的槽位是否参与快速转移与双击收集, 属于弱一致的配置
    private volatile boolean frozen; // 玩家侧只读, 玩家经窗口的点击与拖拽一律不成立, 程序写入与外部同步不受影响, 属于弱一致的配置
    private volatile boolean fireBukkitInventoryEvents = true; // 本 Inventory 参与的交互是否派发 Bukkit 事件
    // 交互事件
    private final ObservableDispatcher<SparrowInventoryClickEvent> clickEvents = new ObservableDispatcher<>();
    private final ObservableDispatcher<InventoryBundleSelectEvent> bundleSelectEvents = new ObservableDispatcher<>();
    // 懒加载的订阅通道与外部视图
    @Nullable private volatile MutableSignal<Long> contentSignal;      // 第一次调用 contentSignal() 时创建, 只由本 Inventory 的 post 订阅和退役递增
    @Nullable private volatile InventoryUpdateChannel updateChannel;   // 第一次订阅事务更新或开启串行 Post 时创建
    @Nullable private volatile org.bukkit.inventory.Inventory bukkitView; // 懒加载的 Bukkit 包装实例, 同一 Inventory 恒为同一个实例.

    SparrowInventory(@Nullable ItemStack @NotNull [] initial) {
        // 初始内容复制后归 Inventory 独占, 空物品折为 null.
        @Nullable ItemStack[] slots = new ItemStack[initial.length];
        for (int i = 0; i < initial.length; i++) {
            slots[i] = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(initial[i]));
        }
        this.state = slots;
        this.naturalOrder = SlotOrder.natural(initial.length);
        this.visual = new InventoryVisualImpl(this.bindings, initial.length);
        @SuppressWarnings("unchecked")
        @Nullable Predicate<ItemStack>[] placementRulesBySlot = (Predicate<ItemStack>[]) new Predicate<?>[initial.length];
        this.placementRulesBySlot = placementRulesBySlot;
    }

    public int size() {
        return this.state.length;
    }

    /**
     * 一次性读出全部槽位, 得到当前时刻的独立副本.
     *
     * @return 按槽号排列的物品副本数组, 空槽位置为 {@code null}
     */
    public @Nullable ItemStack @NotNull [] snapshot() {
        // 先把 volatile 引用抓到局部变量, 整个复制过程读的都是同一份状态数组.
        @Nullable ItemStack[] snapshot = this.state;
        @Nullable ItemStack[] copy = new ItemStack[snapshot.length];
        for (int i = 0; i < snapshot.length; i++) {
            copy[i] = ItemUtils.copyOrNull(snapshot[i]);
        }
        return copy;
    }

    /**
     * 零物品拷贝地读出全部槽位, 直接返回当前内部状态数组.
     * <p><strong>返回数组及其元素只读, 不得修改或持有</strong>.
     *
     * @return 按槽号排列的内部物品引用, 空槽位置为 {@code null}
     */
    public @Nullable ItemStack @NotNull [] unsafeSnapshot() {
        return this.state;
    }

    /**
     * 返回指定类别的批量操作按什么顺序遍历槽位.
     *
     * @param category 操作类别
     * @return 对应类别使用的遍历顺序
     */
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        return this.naturalOrder;
    }

    /**
     * 把槽位换算成 {@link SlotKey}.
     * <p>两个 Inventory 的两个槽位给出同一个 SlotKey, 就说明它们最终写的是同一格.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 该槽的 SlotKey
     */
    @NotNull
    public SlotKey physicalKey(int slot) {
        return new SlotKey(this, slot);
    }

    /**
     * 替换适用于所有未声明逐槽规则的槽位放入规则.
     * 规则收到完整原始输入. 传入 {@code null} 表示这些槽位全部放行.
     * 规则异常会原样传播, 当前规划不会派发事件或提交事务.
     * <p><strong>规则拿到的是内部只读引用, 不得修改或持有</strong>.
     *
     * @param rule 新的全局放入规则, {@code null} 表示放行
     */
    public void setPlacementRule(@Nullable Predicate<@NotNull ItemStack> rule) {
        this.placementRule = rule;
    }

    @Nullable
    public Predicate<ItemStack> getPlacementRule() {
        return this.placementRule;
    }

    /**
     * 替换一个槽位的显式放入规则. 该规则完全覆盖全局规则.
     * 传入 {@code null} 会清除逐槽覆盖, 使该槽重新使用全局规则.
     * <p><strong>规则拿到的是内部只读引用, 不得修改或持有</strong>.
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

    @Nullable
    public Predicate<ItemStack> getPlacementRule(int slot) {
        Objects.checkIndex(slot, this.size());
        return this.placementRulesBySlot[slot];
    }

    // 同一次规划固定使用一组规则快照.
    @NotNull
    @ApiStatus.Internal
    public IntPredicate placementPredicate(@NotNull ItemStack item) {
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
     * 返回此 Inventory 的视觉配置与失效范围.
     *
     * @return 视觉配置与失效范围
     */
    @NotNull
    public final InventoryVisual visual() {
        return this.visual;
    }

    @Nullable
    public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.visual.visualizerProvider();
    }

    /**
     * 设置全局视觉映射. 映射接收真实内容, 返回 {@code null} 时继续使用真实内容或空槽背景.
     * <p>逐槽映射优先于本映射. 映射只影响展示, <strong>必须支持多个 Window 线程并发调用</strong>.
     * 异常交给渲染层上报, 该槽保留上次显示结果.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示不参与这一层
     */
    public void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.visual.setVisualizerProvider(visualizerProvider);
    }

    /**
     * 设置容器全局视觉映射, 并指定提供器给出结果前显示的占位.
     * <p>提供器当场给出结果时不会显示占位, 其余约定见 {@link #setVisualizerProvider(Function)}.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示不参与这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示该槽真实内容
     */
    public void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        this.visual.setVisualizerProvider(visualizerProvider, placeholder);
    }

    /**
     * 使用直接返回 ItemStack 的函数设置全局视觉映射.
     *
     * @param visualizer 新的全局物品映射, {@code null} 表示不参与这一层
     */
    public void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visual.setVisualizerItem(visualizer);
    }

    @Nullable
    public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider(int slot) {
        return this.visual.visualizerProvider(slot);
    }

    /**
     * 设置一个槽位的视觉映射. 非空结果直接采用, {@code null} 结果继续使用全局映射.
     *
     * @param slot 槽位序号
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.visual.setVisualizerProvider(slot, visualizerProvider);
    }

    /**
     * 设置一个槽位的视觉映射与首次结果前的占位.
     * <p>约定与 {@link #setVisualizerProvider(int, Function)} 相同.
     *
     * @param slot 槽位序号
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示该槽真实内容
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        this.visual.setVisualizerProvider(slot, visualizerProvider, placeholder);
    }

    /**
     * 使用直接返回 ItemStack 的函数设置一个槽位的视觉映射.
     * 映射返回 {@code null} 表示放行. 返回空 ItemStack 表示覆盖为空视觉.
     *
     * @param slot 槽位序号
     * @param visualizer 新的逐槽物品映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public void setVisualizerItem(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visual.setVisualizerItem(slot, visualizer);
    }

    public void setBackground(@Nullable ItemProvider background) {
        this.visual.background(background);
    }

    public void setBackgroundItem(@NotNull ItemStack background) {
        this.visual.backgroundItem(background);
    }

    @Nullable
    public ItemProvider getBackground() {
        return this.visual.background();
    }

    /**
     * 返回指定类别的操作挑选目标 Inventory 时使用的优先级, 数值越大越优先.
     *
     * @param category 操作类别
     * @return 对应类别的优先级
     */
    public int operationPriority(@NotNull OperationCategory category) {
        return switch (category) {
            case ADD -> this.addOperationPriority;
            case COLLECT -> this.collectOperationPriority;
            case OTHER -> this.otherOperationPriority;
        };
    }

    /**
     * 设置指定类别的操作挑选目标 Inventory 时使用的优先级, 越大越先被选中.
     *
     * @param category 操作类别
     * @param priority 优先级, 越大越优先
     */
    public void operationPriority(@NotNull OperationCategory category, int priority) {
        switch (category) {
            case ADD -> this.addOperationPriority = priority;
            case COLLECT -> this.collectOperationPriority = priority;
            case OTHER -> this.otherOperationPriority = priority;
        }
    }

    /**
     * 一次设置全部三个类别的优先级.
     *
     * @param priority 优先级, 越大越优先
     */
    public void operationPriority(int priority) {
        this.addOperationPriority = priority;
        this.collectOperationPriority = priority;
        this.otherOperationPriority = priority;
    }

    public void clearOperationPriority(@NotNull OperationCategory category) {
        switch (category) {
            case ADD -> this.addOperationPriority = 0;
            case COLLECT -> this.collectOperationPriority = 0;
            case OTHER -> this.otherOperationPriority = 0;
        }
    }

    public void clearOperationPriority() {
        this.addOperationPriority = 0;
        this.collectOperationPriority = 0;
        this.otherOperationPriority = 0;
    }

    public boolean includeObscuredSlots() {
        return this.includeObscuredSlots;
    }

    /**
     * 设置未被 Pane 展示的槽位是否参与快速转移与双击收集.
     * <p>默认关闭. Pane 冻结槽展示的槽位始终不参与.
     *
     * @param includeObscuredSlots 未展示槽位是否参与点击语义
     */
    public void includeObscuredSlots(boolean includeObscuredSlots) {
        this.includeObscuredSlots = includeObscuredSlots;
    }

    public boolean frozen() {
        return this.frozen;
    }

    /**
     * 返回本 Inventory 是否已经退役.
     * <p>退役后读取为空, 写入失败, 快速转移与双击收集也会排除它.
     * 只有 {@link ReferencingInventory} 会退役, 其余实现恒为 {@code false}.
     *
     * @return 已经退役时返回 true
     */
    public boolean retired() {
        return false;
    }

    /**
     * 设置本 Inventory 是否玩家侧只读.
     * <p>冻结后玩家交互不产生候选或事件, 也不把本 Inventory 作为转移来源或目标.
     * 程序写入与外部同步仍然生效.
     *
     * @param frozen 是否玩家侧只读
     */
    public void frozen(boolean frozen) {
        this.frozen = frozen;
    }

    /**
     * 涉及本 Inventory 的交互是否会派发 Bukkit 事件.
     * <p>默认开启. 交互涉及多个 Inventory 时, 任一参与者开启即可触发事件.
     *
     * @return 是否应在交互时触发 Bukkit 的相关事件
     */
    public boolean fireBukkitInventoryEvents() {
        return this.fireBukkitInventoryEvents;
    }

    public void fireBukkitInventoryEvents(boolean fireBukkitInventoryEvents) {
        this.fireBukkitInventoryEvents = fireBukkitInventoryEvents;
    }

    /**
     * 读取指定槽位的物品, 空槽返回 {@code null}.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 槽内物品的副本, 空槽为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    public ItemStack itemAt(int slot) {
        @Nullable ItemStack[] snapshot = this.state;
        return ItemUtils.copyOrNull(snapshot[slot]);
    }

    /**
     * 零拷贝地读取指定槽位的物品, 空槽返回 {@code null}.
     * <p><strong>返回值只限当前调用栈读取, 不得修改或持有</strong>.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 槽内的内部物品引用, 空槽为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    public ItemStack unsafeItemAt(int slot) {
        @Nullable ItemStack[] snapshot = this.state;
        return snapshot[slot];
    }

    /**
     * 指定槽位自身的堆叠上限, 不含物品自带的堆叠上限.
     * 放入物品时真正生效的上限是两者的较小值.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 该槽位的堆叠上限
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public int slotMaxStackSize(int slot) {
        Objects.checkIndex(slot, this.size());
        return DEFAULT_MAX_STACK_SIZE;
    }

    public boolean isFull() {
        ItemStack[] snapshot = this.unsafeSnapshot();
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack item = snapshot[i];
            if (item == null || item.getAmount() < Math.min(this.slotMaxStackSize(i), item.getMaxStackSize())) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty() {
        ItemStack[] snapshot = this.unsafeSnapshot();
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] != null) {
                return false;
            }
        }
        return true;
    }

    public boolean hasEmptySlot() {
        ItemStack[] snapshot = this.unsafeSnapshot();
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否存在 matcher 选中的物品.
     * <p><strong>matcher 拿到内部只读引用, 不得修改或持有</strong>.
     *
     * @param matcher 判断物品是否符合条件的只读函数
     * @return 至少有一个物品符合条件时返回 {@code true}
     */
    public boolean contains(@NotNull Predicate<? super ItemStack> matcher) {
        ItemStack[] snapshot = this.unsafeSnapshot();
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack item = snapshot[i];
            if (item != null && matcher.test(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsSimilar(@NotNull ItemStack template) {
        Object handle = ItemUtils.getItemStackHandle(template);
        return this.contains(item -> ItemUtils.isSimilarToHandle(item, handle));
    }

    /**
     * 统计 matcher 选中的物品堆数量, 不累加堆内物品数量.
     * <p><strong>matcher 拿到内部只读引用, 不得修改或持有</strong>.
     *
     * @param matcher 判断物品是否符合条件的只读函数
     * @return 符合条件的非空槽数量
     */
    public int count(@NotNull Predicate<? super ItemStack> matcher) {
        ItemStack[] snapshot = this.unsafeSnapshot();
        int count = 0;
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack item = snapshot[i];
            if (item != null && matcher.test(item)) {
                count++;
            }
        }
        return count;
    }

    public int countSimilar(@NotNull ItemStack template) {
        Object handle = ItemUtils.getItemStackHandle(template);
        return this.count(item -> ItemUtils.isSimilarToHandle(item, handle));
    }

    public boolean hasItem(int slot) {
        return this.itemAt(slot) != null;
    }

    public int itemAmount(int slot) {
        ItemStack item = this.itemAt(slot);
        return item == null ? 0 : item.getAmount();
    }

    /**
     * 直接覆盖写入单个槽位, {@code null} 表示清空.
     * 即使新值与当前值相等也会产生事务与事件.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public TransactionResult setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        return this.commitSingle(reason, slot, item, false);
    }

    /**
     * 直接覆盖写入单个槽位, 以 {@link UpdateReason.Program} 的名义.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public TransactionResult setItem(int slot, @Nullable ItemStack item) {
        return this.setItem(UpdateReason.Program.INSTANCE, slot, item);
    }

    /**
     * 与 {@link #setItem} 相同, 但跳过 pre 事件且无法被取消. post 事件仍会正常派发.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public TransactionResult forceSetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        return this.commitSingle(reason, slot, item, true);
    }

    /**
     * 与 {@link #setItem(int, ItemStack)} 相同, 但跳过 pre 事件且无法被取消. post 事件仍会正常派发.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public TransactionResult forceSetItem(int slot, @Nullable ItemStack item) {
        return this.forceSetItem(UpdateReason.Program.INSTANCE, slot, item);
    }

    private TransactionResult commitSingle(UpdateReason reason, int slot, @Nullable ItemStack item, boolean bypassPre) {
        Objects.checkIndex(slot, this.size());
        PlannedRoot basis = this.openPlanForWrite();
        @Nullable ItemStack[] planned = basis.planned();
        SlotChange delta = new SlotChange(slot, planned[slot], item);
        return InventoryTransactions.commit(
                reason,
                List.of(new TransactionScope(basis, List.of(delta))),
                bypassPre
        );
    }

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
    public AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item) {
        Objects.checkIndex(slot, this.size());
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        PlannedRoot basis = this.openPlanForWrite();
        InventoryPlanner.AddPlan plan = InventoryPlanner.planPut(basis.planned()[slot], input, slot, this::slotMaxStackSize, this.placementPredicate(input));
        if (plan.deltas().isEmpty()) {
            return new AddResult(EMPTY_COMMITTED, plan.remaining());
        }
        TransactionResult result = this.commitScoped(reason, basis, plan.deltas());
        return new AddResult(result, result instanceof TransactionResult.Committed ? plan.remaining() : input.getAmount());
    }

    /**
     * 往指定槽位尽量放入物品, 以 {@link UpdateReason.Program} 的名义.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public AddResult putItem(int slot, @NotNull ItemStack item) {
        return this.putItem(UpdateReason.Program.INSTANCE, slot, item);
    }

    /**
     * 读, 改, 写指定槽位.
     * modifier 接收当前物品的副本, 返回 {@code null} 表示清空.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param modifier 接收旧物品副本并返回新物品的函数
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        Objects.checkIndex(slot, this.size());
        PlannedRoot basis = this.openPlanForWrite();
        @Nullable ItemStack[] planned = basis.planned();
        // modifier 在锁外运行, 输入与返回值都会复制.
        @Nullable ItemStack modified = modifier.apply(ItemUtils.copyOrNull(planned[slot]));
        return this.commitScoped(reason, basis, List.of(new SlotChange(slot, planned[slot], modified)));
    }

    /**
     * 增减槽内物品数量. 减少时最低到 0, 增加时最高到有效堆叠上限.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param change 数量变化, 正数为增加, 负数为减少
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change) {
        Objects.checkIndex(slot, this.size());
        PlannedRoot basis = this.openPlanForWrite();
        @Nullable SlotChange delta = InventoryPlanner.planAmountChange(basis.planned()[slot], slot, change, this::slotMaxStackSize);
        if (delta == null) {
            return EMPTY_COMMITTED;
        }
        return this.commitScoped(reason, basis, List.of(delta));
    }

    // 把规划好的变更作为只涉及本 Inventory 的一笔事务提交, basis 兼任提交时的并发校验依据.
    private TransactionResult commitScoped(UpdateReason reason, PlannedRoot basis, List<SlotChange> deltas) {
        return InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, deltas)), false);
    }

    /**
     * 按 ADD 遍历顺序把物品尽量放进 Inventory, 先合并相似物品堆, 再占用空槽.
     * 整个放入过程作为一次事务提交.
     *
     * @param reason 本次修改的原因
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     */
    @NotNull
    public AddResult add(@NotNull UpdateReason reason, @NotNull ItemStack item) {
        // 先复制物品再判断是否为空, 保证后续读取的对象不受调用方修改影响
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        PlannedRoot basis = this.openPlanForWrite();
        // 在规划内容上计算, 先合并相似的未满堆, 再占空槽
        InventoryPlanner.AddPlan plan = InventoryPlanner.planAdd(
                basis.planned(),
                input,
                this.iterationOrder(OperationCategory.ADD),
                this::slotMaxStackSize,
                this.placementPredicate(input)
        );
        if (plan.deltas().isEmpty()) {
            return new AddResult(EMPTY_COMMITTED, plan.remaining());
        }
        // 整组槽位变更一次提交, 没提交成功视为一个都没放进去
        TransactionResult result = InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, plan.deltas())), false);
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
        PlannedRoot basis = this.openPlanForWrite();
        // 在规划内容上计算, 先收未满堆, 不够再收满堆
        InventoryPlanner.TakePlan plan = InventoryPlanner.planCollect(
                basis.planned(),
                sample,
                upTo,
                this.iterationOrder(OperationCategory.COLLECT),
                null,
                this::slotMaxStackSize
        );
        if (plan.deltas().isEmpty()) {
            return new CollectResult(EMPTY_COMMITTED, 0);
        }
        // 整组槽位变更一次提交, 没提交成功视为一个都没收到
        TransactionResult result = InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, plan.deltas())), false);
        return new CollectResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    /**
     * 按 OTHER 遍历顺序移除 matcher 选中的物品, 最多移除 {@code upTo} 个, 整个移除过程作为一次事务提交.
     * <p><strong>matcher 拿到内部只读引用, 不得修改或持有</strong>.
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
        PlannedRoot basis = this.openPlanForWrite();
        // 在规划内容上计算要动哪些槽, matcher 由规划器在锁外逐个调用
        InventoryPlanner.TakePlan plan = InventoryPlanner.planRemove(basis.planned(), matcher, upTo, this.iterationOrder(OperationCategory.OTHER));
        if (plan.deltas().isEmpty()) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        // 整组槽位变更一次提交, 没提交成功视为一个都没移除
        TransactionResult result = InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, plan.deltas())), false);
        return new RemoveResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    /**
     * 判断 Inventory 能否完整装下给定物品.
     *
     * @param item 要检查的物品
     * @return 能完整装下时返回 {@code true}
     */
    public boolean mayPlace(@NotNull ItemStack item) {
        return this.simulateAdd(item) == 0;
    }

    /**
     * 判断 Inventory 能否按参数顺序完整装下全部物品.
     *
     * @param items 要检查的物品
     * @return 全部能装下时返回 {@code true}
     */
    public boolean mayPlace(ItemStack @NotNull ... items) {
        int[] remaining = this.simulateAdd(items);
        for (int i = 0; i < remaining.length; i++) {
            if (remaining[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断 Inventory 能否完整取出给定物品.
     * <p>{@code item} 同时参与相似判断并提供需要取出的数量.
     * {@link #simulateCollect(ItemStack, int)} 的样板则只管相似判断, 数量单独由 {@code upTo} 指定.
     * <p>取出侧没有与放入规则对应的过滤, 结果只取决于 Inventory 里现有的内容.
     *
     * @param item 要检查的物品, 它的数量就是需要取出的数量
     * @return 能完整取出时返回 {@code true}
     */
    public boolean mayPickup(@NotNull ItemStack item) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (sample == null) {
            return true;
        }
        return this.simulateCollect(sample, sample.getAmount()) == sample.getAmount();
    }

    /**
     * 判断 Inventory 能否按参数顺序完整取出全部物品.
     * <p>多个物品共用同一份规划内容, 前面已经算作取走的部分, 后面不会再认领一次.
     *
     * @param items 要检查的物品, 各自的数量就是需要取出的数量
     * @return 全部能取出时返回 {@code true}
     */
    public boolean mayPickup(ItemStack @NotNull ... items) {
        @Nullable ItemStack[] working = this.openPlan().planned().clone();
        for (int i = 0; i < items.length; i++) {
            @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(items[i]));
            if (sample == null) {
                continue;
            }
            int required = sample.getAmount();
            InventoryPlanner.TakePlan plan = InventoryPlanner
                    .planCollect(working, sample, required, this.iterationOrder(OperationCategory.COLLECT), null, this::slotMaxStackSize);
            if (plan.taken() != required) {
                return false;
            }
            // 把这一件取走的结果写回规划内容, 同一堆物品不会被后面的物品重复认领.
            List<SlotChange> deltas = plan.deltas();
            for (int j = 0; j < deltas.size(); j++) {
                SlotChange delta = deltas.get(j);
                working[delta.slot()] = delta.unsafeAfter();
            }
        }
        return true;
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
        return InventoryPlanner
                .planAdd(this.openPlan().planned(), input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize, this.placementPredicate(input))
                .remaining();
    }

    /**
     * 在同一份规划内容上按参数顺序连续试算放入多个物品.
     *
     * @param items 要试算的物品
     * @return 与参数顺序一致的剩余数量数组
     */
    public int[] simulateAdd(ItemStack @NotNull ... items) {
        return this.simulateAdd(Arrays.asList(items));
    }

    /**
     * 在同一份规划内容上按列表顺序连续试算放入多个物品.
     *
     * @param items 要试算的物品
     * @return 与列表顺序一致的剩余数量数组
     */
    public int[] simulateAdd(@NotNull List<? extends ItemStack> items) {
        @Nullable ItemStack[] working = this.openPlan().planned().clone();
        int[] remaining = new int[items.size()];
        int index = 0;
        for (ItemStack item : items) {
            @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
            if (input == null) {
                index++;
                continue;
            }
            InventoryPlanner.AddPlan plan = InventoryPlanner
                    .planAdd(working, input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize, this.placementPredicate(input));
            remaining[index] = plan.remaining();
            List<SlotChange> deltas = plan.deltas();
            for (int j = 0; j < deltas.size(); j++) {
                SlotChange delta = deltas.get(j);
                working[delta.slot()] = delta.unsafeAfter();
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
        return InventoryPlanner.planCollect(this.openPlan().planned(), sample, upTo, this.iterationOrder(OperationCategory.COLLECT), null, this::slotMaxStackSize).taken();
    }

    /**
     * 让 ReferencingInventory 同步最新内容.
     * <p>其他实现调用无效果. <strong>调用 ReferencingInventory 时必须处于外部存储所属线程</strong>.
     */
    public void refresh() {
    }

    // 写规划钩子, ReferencingInventory 在这里同步外部内容.
    @ApiStatus.Internal
    public void prepareWrite() {
    }

    /**
     * 把 SparrowInventory 包装成原生 CraftInventory, 同一个 Inventory 永远返回同一个包装实例.
     * CraftInventory 背后的 NMS Container 直接代理本 Inventory, 槽位写入会走 Sparrow 的事务流程.
     * 观看者, 持有者和位置均返回 {@code null}, 类型固定为 CHEST.
     *
     * @return CraftInventory
     */
    @NotNull
    @ApiStatus.Experimental
    public org.bukkit.inventory.Inventory asBukkitInventory() {
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

    @ApiStatus.Internal
    public boolean hasClickObservers() {
        return this.clickEvents.subscriptionCount() != 0;
    }

    /**
     * 订阅玩家点击本 Inventory 连接槽的事件.
     * 事件在候选形成后, 事务 Pre 前派发, 取消会阻止候选提交.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    public Subscription subscribeClick(@NotNull Observer<? super SparrowInventoryClickEvent> observer) {
        return this.clickEvents.subscribe(observer);
    }

    @ApiStatus.Internal
    public void publishClick(@NotNull SparrowInventoryClickEvent event) {
        this.clickEvents.publish(event);
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

    @ApiStatus.Internal
    public void publishBundleSelect(@NotNull InventoryBundleSelectEvent event) {
        this.bundleSelectEvents.publish(event);
    }

    /**
     * 订阅事务提交前的事件, 处理器可以取消整个事务, 当前 Inventory 没有槽位变更时不会通知.
     * <p><strong>处理器在提交线程同步调用, 并且可能被多个线程并发调用</strong>.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    public Subscription subscribePreUpdate(@NotNull Observer<? super InventoryPreUpdateEvent> observer) {
        return this.updateChannel().subscribePre(observer);
    }

    /**
     * 订阅事务提交后的事件, 没有槽位变更时不会通知.
     * <p><strong>处理器在提交线程同步调用, 并且可能被多个提交线程并发调用</strong>.
     * <p>不同事务的 Post 不保证按提交顺序到达, 需要判断新旧时使用 {@link InventoryPostUpdateEvent#version()}.
     * 最外层事务返回前会完成本次 Post. Post 处理器里的嵌套事务会先返回, 它的 Post 排在当前完整批次之后,
     * 并在最外层 Post 派发退出前完成.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    public Subscription subscribePostUpdate(@NotNull Observer<? super InventoryPostUpdateEvent> observer) {
        return this.updateChannel().subscribePost(observer);
    }

    /**
     * 返回本 Inventory 的内容修订计数. 每笔改动本 Inventory 的事务提交后递增一次, 并向下游发出失效.
     * <p>Signal 惰性创建且实例稳定, 数值只用来携带失效.
     * <p><strong>失效在提交线程同步派发</strong>. {@link Signal#mapDistinct} 会把重算放进提交收尾,
     * 较重的计算应使用拉取时求值的 {@link Signal#map} 或 {@link Signals#combine}.
     *
     * @return 内容修订计数
     */
    @NotNull
    public final Signal<Long> contentSignal() {
        MutableSignal<Long> signal = this.contentSignal;
        if (signal == null) {
            synchronized (this) {
                signal = this.contentSignal;
                if (signal == null) {
                    MutableSignal<Long> created = Signal.of(0L);
                    // 订阅凭证本 Inventory 的事务订阅器持有, 与本 Inventory 同生命周期.
                    this.subscribePostUpdate(ignoredEvent -> created.update(revision -> revision + 1L));
                    this.contentSignal = created;
                    signal = created;
                }
            }
        }
        return signal;
    }

    // 退役等无槽位事务的状态变化也需要使内容 Signal 失效.
    final void updateContentSignal() {
        MutableSignal<Long> signal = this.contentSignal;
        if (signal != null) {
            signal.update(revision -> revision + 1L);
        }
    }

    /**
     * 绑定到指定的 Signal, Signal 将会持有本类的弱引用.
     * Signal 失效时触发回调.
     * <p>绑定不补发当前值, 第一次回调发生在下一次标脏.
     * <p>绑定由本对象持有, 本对象被回收时一并消失, {@code callback} 捕获的对象也随之释放.
     *
     * @param signal 数据源
     * @param callback 失效回调
     * @return 订阅凭证, 可用于提前解绑.
     */
    @NotNull
    public final Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super SparrowInventory> callback) {
        Objects.requireNonNull(callback, "callback");
        return this.bindings.bind(() -> signal.onDirty(() -> callback.accept(this)));
    }

    // 更新通道在首次订阅或配置串行 Post 时创建.
    @NotNull
    InventoryUpdateChannel updateChannel() {
        InventoryUpdateChannel channel = this.updateChannel;
        if (channel == null) {
            synchronized (this) {
                channel = this.updateChannel;
                if (channel == null) {
                    channel = new InventoryUpdateChannel(this);
                    this.updateChannel = channel;
                }
            }
        }
        return channel;
    }

    // 事务引擎凭它找出本笔事务要通知谁, 从未订阅过的 Inventory 不值得为它建一个空通道, 所以只看不建.
    @Nullable
    @ApiStatus.Internal
    public InventoryUpdateChannel updateChannelIfPresent() {
        return this.updateChannel;
    }

    // 纯读用途的规划基准, 给 simulate 这类零副作用的路径使用.
    @NotNull
    @ApiStatus.Internal
    public PlannedRoot openPlan() {
        return new Stm(this, this.state);
    }

    // 写路径的规划基准, 读内容之前先走一遍写前准备.
    @NotNull
    @ApiStatus.Internal
    public PlannedRoot openPlanForWrite() {
        this.prepareWrite();
        return this.openPlan();
    }

    // 内部状态数组同时充当规划快照与乐观校验凭据.
    private static final class Stm extends PlannedRoot {

        private Stm(@NotNull SparrowInventory inventory, @Nullable ItemStack @NotNull [] planned) {
            super(inventory, planned);
        }

        @Override
        @NotNull
        protected StateLock stateLock() {
            SparrowInventory inventory = this.inventory();
            return new StateLock(inventory.writeLock, inventory.lockOrder);
        }

        @Override
        public boolean isStale() {
            return this.inventory().state != this.planned();
        }

        @Override
        protected @Nullable ItemStack @NotNull [] buildNextState(@NotNull List<SlotChange> deltas) {
            // isStale 刚在同一临界区内通过, planned 与当前状态是同一个数组, 克隆它即克隆当前状态.
            @Nullable ItemStack[] next = this.planned().clone();
            for (int i = 0; i < deltas.size(); i++) {
                SlotChange delta = deltas.get(i);
                // 等值写入保留原元素, 物品实例只随内容变化而更换.
                @Nullable ItemStack after = delta.unsafeAfter();
                @Nullable ItemStack current = next[delta.slot()];
                next[delta.slot()] = ItemUtils.isContentEqual(current, after) ? current : after;
            }
            return next;
        }

        @Override
        protected void swapTo(@Nullable ItemStack @Nullable [] nextState) {
            if (nextState != null) {
                this.inventory().state = nextState;
            }
        }

        @Override
        protected void land(@NotNull List<SlotChange> deltas) {
        }
    }
}
