package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.ObservableDispatcher;
import net.momirealms.sparrow.ui.inventory.event.InventoryBundleSelectEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.SparrowInventoryClickEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.CollectResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
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
import net.momirealms.sparrow.ui.window.Window;
import net.momirealms.sparrow.ui.visual.InventoryVisual;
import net.momirealms.sparrow.ui.visual.InventoryVisualImpl;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 受事务保护并可订阅内容变更的 Inventory 基类, 空槽使用 {@code null}.
 * <p>普通读取返回副本. 名称以 {@code unsafe} 开头的入口会暴露内部对象, <strong>只读, 不得修改或持有</strong>.
 * 内容相等的写入仍可产生事务和事件, 实现也可以保留原物品实例.
 * <p>try 方法按 AccessRule 审核候选, 提交可取消、可冲突的请求; 无 try 的修改方法取得写权限后基于当前内容执行,
 * 跳过规则和 Pre, 派发 Post 时已经交还写权限. UpdateReason 只记录来源.
 * 存储落地或通知抛出异常时, 已生效的修改不会回滚.
 * <p>写权限在两个实现上是两回事, 无 try 方法的原子性也跟着分开.
 * {@link VirtualInventory} 用一把写锁串行化, 同一个槽位连续两次 {@code changeAmount(slot, 1)} 一定各记一次.
 * {@link ReferencingInventory} 不加锁, 串行完全依赖<strong>调用方只从存储所属线程进来</strong>;
 * 两个线程同时进来会读到同一个旧值并各写一遍, 丢掉的那次增量不体现在返回值里, 也不会报错.
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
    @Nullable private volatile AccessRule accessRule; // 全局请求准入规则, null 表示放行
    @Nullable private volatile AccessRule @NotNull [] accessRulesBySlot; // 槽级请求准入规则, 与全局规则取交集
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
        @Nullable AccessRule[] accessRulesBySlot = new AccessRule[initial.length];
        this.accessRulesBySlot = accessRulesBySlot;
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
     * 设置所有槽位的请求准入规则, 与槽级规则取交集; 权威修改不经过规则.
     * <p><strong>规则与上下文物品只读, 不得产生业务副作用</strong>. 异常原样传播.
     *
     * @param rule 全局规则, null 表示放行
     */
    public void setAccessRule(@Nullable AccessRule rule) {
        this.accessRule = rule;
    }

    @Nullable
    public AccessRule getAccessRule() {
        return this.accessRule;
    }

    /**
     * 设置槽级请求准入规则, 不覆盖全局规则.
     *
     * @param slot 槽位序号
     * @param rule 槽级规则, null 表示只受全局规则约束
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public void setAccessRule(int slot, @Nullable AccessRule rule) {
        Objects.checkIndex(slot, this.size());
        @Nullable AccessRule[] rules = this.accessRulesBySlot.clone();
        rules[slot] = rule;
        this.accessRulesBySlot = rules;
    }

    @Nullable
    public AccessRule getAccessRule(int slot) {
        Objects.checkIndex(slot, this.size());
        return this.accessRulesBySlot[slot];
    }

    // 普通槽位变更从前后内容计算流动; 没有规则时不创建上下文.
    @ApiStatus.Internal
    public boolean allowsAccess(@NotNull UpdateReason reason, @Nullable Window window, @NotNull SlotChange change) {
        if (this.accessRule == null && this.accessRulesBySlot[change.slot()] == null) {
            return true;
        }
        int added = change.addedAmount();
        int removed = change.removedAmount();
        return this.allowsAccess(reason, window, change,
                added == 0 ? null : ItemUtils.copyWithAmount(change.unsafeAfter(), added),
                removed == 0 ? null : ItemUtils.copyWithAmount(change.unsafeBefore(), removed));
    }

    // 收纳袋的实际流动由点击语义提供, 全局和槽级规则检查同一个候选.
    @ApiStatus.Internal
    public boolean allowsAccess(@NotNull UpdateReason reason, @Nullable Window window, @NotNull SlotChange change, @Nullable ItemStack addedItem, @Nullable ItemStack removedItem) {
        @Nullable AccessRule global = this.accessRule;
        @Nullable AccessRule local = this.accessRulesBySlot[change.slot()];
        if (global == null && local == null) {
            return true;
        }
        AccessContext context = new AccessContext(this, reason, window, change, addedItem, removedItem);
        return (global == null || global.test(context)) && (local == null || local.test(context));
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
     * 权威覆盖指定槽位, null 表示清空; 即使内容相等也派发 Post.
     * <p>取得写权限后读取当前内容并交换, 不经过放入规则、Pre 或玩家冻结检查.
     *
     * @param reason 仅记录修改来源, 不改变执行模式
     * @param slot 槽位序号, 从 0 开始
     * @param item 要写入的物品, 会复制; null 表示清空
     * @throws IndexOutOfBoundsException 当槽号越界时
     * @throws IllegalStateException 当引用库存已退役时
     */
    public void setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        Objects.checkIndex(slot, this.size());
        @Nullable ItemStack input = ItemUtils.copyOrNull(item);
        InventoryTransactions.mutate(reason, this, basis -> List.of(new SlotChange(slot, basis.planned()[slot], input)), Function.identity());
    }

    /**
     * 以 Program 来源权威覆盖指定槽位.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param item 要写入的物品, null 表示清空
     * @see #setItem(UpdateReason, int, ItemStack)
     */
    public void setItem(int slot, @Nullable ItemStack item) {
        this.setItem(UpdateReason.Program.INSTANCE, slot, item);
    }

    /**
     * 权威地向指定槽位尽量放入物品, 空槽直接放入, 相似堆合并.
     * <p>在写入临界区内计算容量, 不经过放入规则、Pre 或玩家冻结检查.
     *
     * @param reason 仅记录修改来源, 不改变执行模式
     * @param slot 槽位序号, 从 0 开始
     * @param item 要放入的物品, 会复制
     * @return 实际放不下的数量
     * @throws IndexOutOfBoundsException 当槽号越界时
     * @throws IllegalStateException 当引用库存已退役且需要规划写入时
     */
    public int putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item) {
        Objects.checkIndex(slot, this.size());
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return 0;
        }
        return InventoryTransactions.mutate(reason, this,
                basis -> InventoryPlanner.planPut(basis.planned()[slot], input, slot, this::slotMaxStackSize, null),
                InventoryPlanner.AddPlan::deltas).remaining();
    }

    /**
     * 以 Program 来源权威放入指定槽位.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param item 要放入的物品
     * @return 实际放不下的数量
     * @see #putItem(UpdateReason, int, ItemStack)
     */
    public int putItem(int slot, @NotNull ItemStack item) {
        return this.putItem(UpdateReason.Program.INSTANCE, slot, item);
    }

    /**
     * 在同一个写入临界区内读取、修改并覆盖指定槽位, 不经过规则、Pre 或玩家冻结检查.
     * <p>modifier 调用一次, 接收当前物品副本, 返回值也会复制, null 表示清空.
     * <strong>回调只能计算传入物品, 不得读写任何库存</strong>; 回调抛出时不提交.
     *
     * @param reason 仅记录修改来源, 不改变执行模式
     * @param slot 槽位序号, 从 0 开始
     * @param modifier 计算新物品的函数
     * @throws IndexOutOfBoundsException 当槽号越界时
     * @throws IllegalStateException 当引用库存已退役, 或 modifier 在回调中发起了库存写入时
     */
    public void modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        Objects.checkIndex(slot, this.size());
        InventoryTransactions.mutate(reason, this, basis -> {
            @Nullable ItemStack before = basis.planned()[slot];
            return List.of(new SlotChange(slot, before, modifier.apply(ItemUtils.copyOrNull(before))));
        }, Function.identity());
    }

    /**
     * 以 Program 来源权威修改指定槽位.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param modifier 接收当前物品副本并计算新物品的函数
     * @see #modifyItem(UpdateReason, int, UnaryOperator)
     */
    public void modifyItem(int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        this.modifyItem(UpdateReason.Program.INSTANCE, slot, modifier);
    }

    /**
     * 在写入临界区内增减当前槽内数量, 减量最低为 0, 增量最高为有效堆叠上限.
     * <p>不经过规则、Pre 或玩家冻结检查; 空槽和无需变更时返回 0, 不派发 Post.
     *
     * @param reason 仅记录修改来源, 不改变执行模式
     * @param slot 槽位序号, 从 0 开始
     * @param change 数量变化, 正数为增加, 负数为减少
     * @return 实际数量变化, 正数为增加, 负数为减少
     * @throws IndexOutOfBoundsException 当槽号越界时
     * @throws IllegalStateException 当引用库存已退役时
     */
    public int changeAmount(@NotNull UpdateReason reason, int slot, int change) {
        Objects.checkIndex(slot, this.size());
        @Nullable SlotChange delta = InventoryTransactions.mutate(reason, this,
                basis -> InventoryPlanner.planAmountChange(basis.planned()[slot], slot, change, this::slotMaxStackSize),
                changeResult -> changeResult == null ? List.of() : List.of(changeResult));
        return delta == null ? 0 : delta.addedAmount() - delta.removedAmount();
    }

    /**
     * 以 Program 来源权威增减指定槽位数量.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param change 数量变化, 正数为增加, 负数为减少
     * @return 实际数量变化
     * @see #changeAmount(UpdateReason, int, int)
     */
    public int changeAmount(int slot, int change) {
        return this.changeAmount(UpdateReason.Program.INSTANCE, slot, change);
    }

    /**
     * 在写入临界区内按 ADD 顺序尽量放入物品, 先合并相似堆再占空槽.
     * <p>不经过放入规则、Pre 或玩家冻结检查, 无槽位变化时不派发 Post.
     *
     * @param reason 仅记录修改来源, 不改变执行模式
     * @param item 要放入的物品, 会复制
     * @return 实际放不下的数量
     * @throws IllegalStateException 当引用库存已退役且需要规划写入时
     */
    public int add(@NotNull UpdateReason reason, @NotNull ItemStack item) {
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return 0;
        }
        return InventoryTransactions.mutate(reason, this,
                basis -> InventoryPlanner.planAdd(basis.planned(), input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize, null),
                InventoryPlanner.AddPlan::deltas).remaining();
    }

    /**
     * 以 Program 来源权威放入物品.
     *
     * @param item 要放入的物品
     * @return 实际放不下的数量
     * @see #add(UpdateReason, ItemStack)
     */
    public int add(@NotNull ItemStack item) {
        return this.add(UpdateReason.Program.INSTANCE, item);
    }

    /**
     * 在写入临界区内按 COLLECT 顺序收集相似物品, 最多取出 upTo 个.
     * <p>不经过规则、Pre 或玩家冻结检查, 无槽位变化时不派发 Post.
     *
     * @param reason 仅记录修改来源, 不改变执行模式
     * @param template 物品样板, 只用于相似判断
     * @param upTo 最多收集的数量
     * @return 实际收集数量
     * @throws IllegalStateException 当引用库存已退役且需要规划写入时
     */
    public int collect(@NotNull UpdateReason reason, @NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(template));
        if (sample == null || upTo <= 0) {
            return 0;
        }
        return InventoryTransactions.mutate(reason, this,
                basis -> InventoryPlanner.planCollect(basis.planned(), sample, upTo, this.iterationOrder(OperationCategory.COLLECT), null, this::slotMaxStackSize),
                InventoryPlanner.TakePlan::deltas).taken();
    }

    /**
     * 以 Program 来源权威收集相似物品.
     *
     * @param template 物品样板
     * @param upTo 最多收集的数量
     * @return 实际收集数量
     * @see #collect(UpdateReason, ItemStack, int)
     */
    public int collect(@NotNull ItemStack template, int upTo) {
        return this.collect(UpdateReason.Program.INSTANCE, template, upTo);
    }

    /**
     * 在写入临界区内按 OTHER 顺序移除匹配物品, 最多取出 upTo 个.
     * <p>不经过规则、Pre 或玩家冻结检查, 无槽位变化时不派发 Post.
     * <strong>matcher 接收内部只读物品, 不得修改或持有, 也不得读写任何库存</strong>; 回调抛出时不提交.
     *
     * @param reason 仅记录修改来源, 不改变执行模式
     * @param matcher 判断物品是否应被移除的函数
     * @param upTo 最多移除的数量
     * @return 实际移除数量
     * @throws IllegalStateException 当引用库存已退役且需要规划写入, 或 matcher 在回调中发起了库存写入时
     */
    public int remove(@NotNull UpdateReason reason, @NotNull Predicate<@NotNull ItemStack> matcher, int upTo) {
        if (upTo <= 0) {
            return 0;
        }
        return InventoryTransactions.mutate(reason, this,
                basis -> InventoryPlanner.planRemove(basis.planned(), matcher, upTo, this.iterationOrder(OperationCategory.OTHER), null),
                InventoryPlanner.TakePlan::deltas).taken();
    }

    /**
     * 以 Program 来源权威移除匹配物品.
     *
     * @param matcher 判断物品是否应被移除的只读函数
     * @param upTo 最多移除的数量
     * @return 实际移除数量
     * @see #remove(UpdateReason, Predicate, int)
     */
    public int remove(@NotNull Predicate<@NotNull ItemStack> matcher, int upTo) {
        return this.remove(UpdateReason.Program.INSTANCE, matcher, upTo);
    }

    /**
     * 在写入临界区内清空全部槽位, 不经过规则、Pre 或玩家冻结检查.
     * <p>只为非空槽生成变更, 已为空时不派发 Post.
     *
     * @param reason 仅记录修改来源, 不改变执行模式
     * @throws IllegalStateException 当引用库存已退役时
     */
    public void clear(@NotNull UpdateReason reason) {
        InventoryTransactions.mutate(reason, this, basis -> {
            @Nullable ItemStack[] current = basis.planned();
            List<SlotChange> deltas = new ArrayList<>(current.length);
            for (int slot = 0; slot < current.length; slot++) {
                if (current[slot] != null) {
                    deltas.add(new SlotChange(slot, current[slot], null));
                }
            }
            return deltas;
        }, Function.identity());
    }

    /**
     * 以 Program 来源权威清空全部槽位.
     *
     * @see #clear(UpdateReason)
     */
    public void clear() {
        this.clear(UpdateReason.Program.INSTANCE);
    }

    // Bukkit 包装器在同一临界区内读取并取出, 返回实际移除的独立副本.
    @Nullable
    ItemStack takeItem(int slot, int amount) {
        Objects.checkIndex(slot, this.size());
        if (amount <= 0) {
            return null;
        }
        @Nullable SlotChange delta = InventoryTransactions.mutate(UpdateReason.Program.INSTANCE, this,
                basis -> InventoryPlanner.planAmountChange(basis.planned()[slot], slot, -amount, this::slotMaxStackSize),
                change -> change == null ? List.of() : List.of(change));
        return delta == null ? null : ItemUtils.copyWithAmount(delta.unsafeBefore(), delta.removedAmount());
    }

    // 把规划好的变更作为只涉及本 Inventory 的一笔请求提交.
    private TransactionResult commitScoped(UpdateReason reason, PlannedRoot basis, List<SlotChange> deltas) {
        for (int i = 0; i < deltas.size(); i++) {
            if (!this.allowsAccess(reason, null, deltas.get(i))) {
                return TransactionResult.Cancelled.INSTANCE;
            }
        }
        return InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, deltas)), false);
    }

    /**
     * 请求覆盖写入单个槽位, {@code null} 表示清空.
     * 即使新值与当前值相等也会产生事务与事件.
     * <p>Pre 可以取消或编辑候选结果, 规划基准失效时返回冲突结果.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public TransactionResult trySetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        Objects.checkIndex(slot, this.size());
        PlannedRoot basis = this.openPlanForWrite();
        return this.commitScoped(reason, basis, List.of(new SlotChange(slot, basis.planned()[slot], item)));
    }

    /**
     * 请求覆盖指定槽位, 以 {@link UpdateReason.Program} 的名义.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     * @see #trySetItem(UpdateReason, int, ItemStack)
     */
    @NotNull
    public TransactionResult trySetItem(int slot, @Nullable ItemStack item) {
        return this.trySetItem(UpdateReason.Program.INSTANCE, slot, item);
    }

    /**
     * 往指定槽位尽量放入物品.
     * 空槽会直接放入, 相似物品会合并, 不相似时全部数量都会剩余.
     * <p>Pre 可以取消或编辑候选结果, 规划基准失效时返回冲突结果.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public AddResult tryPutItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item) {
        Objects.checkIndex(slot, this.size());
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        PlannedRoot basis = this.openPlanForWrite();
        InventoryPlanner.AddPlan plan = InventoryPlanner.planPut(basis.planned()[slot], input, slot, this::slotMaxStackSize, delta -> this.allowsAccess(reason, null, delta));
        if (plan.deltas().isEmpty()) {
            return new AddResult(EMPTY_COMMITTED, plan.remaining());
        }
        TransactionResult result = InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, plan.deltas())), false);
        return new AddResult(result, result instanceof TransactionResult.Committed ? plan.remaining() : input.getAmount());
    }

    /**
     * 请求往指定槽位尽量放入物品, 以 {@link UpdateReason.Program} 的名义.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     * @throws IndexOutOfBoundsException 当槽号越界时
     * @see #tryPutItem(UpdateReason, int, ItemStack)
     */
    @NotNull
    public AddResult tryPutItem(int slot, @NotNull ItemStack item) {
        return this.tryPutItem(UpdateReason.Program.INSTANCE, slot, item);
    }

    /**
     * 请求根据旧物品副本修改指定槽位.
     * modifier 在提交锁外调用一次, 接收当前物品的副本, 返回 {@code null} 表示清空.
     * <p>Pre 可以取消或编辑候选结果, 规划基准失效时返回冲突结果.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param modifier 接收旧物品副本并返回新物品的函数
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public TransactionResult tryModifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        Objects.checkIndex(slot, this.size());
        PlannedRoot basis = this.openPlanForWrite();
        @Nullable ItemStack[] planned = basis.planned();
        // modifier 在锁外运行, 输入与返回值都会复制.
        @Nullable ItemStack modified = modifier.apply(ItemUtils.copyOrNull(planned[slot]));
        return this.commitScoped(reason, basis, List.of(new SlotChange(slot, planned[slot], modified)));
    }

    /**
     * 请求根据旧物品副本修改指定槽位, 以 {@link UpdateReason.Program} 的名义.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param modifier 接收旧物品副本并返回新物品的函数
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     * @see #tryModifyItem(UpdateReason, int, UnaryOperator)
     */
    @NotNull
    public TransactionResult tryModifyItem(int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        return this.tryModifyItem(UpdateReason.Program.INSTANCE, slot, modifier);
    }

    /**
     * 增减槽内物品数量. 减少时最低到 0, 增加时最高到有效堆叠上限.
     * <p>Pre 可以取消或编辑候选结果, 规划基准失效时返回冲突结果.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param change 数量变化, 正数为增加, 负数为减少
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    public TransactionResult tryChangeAmount(@NotNull UpdateReason reason, int slot, int change) {
        Objects.checkIndex(slot, this.size());
        PlannedRoot basis = this.openPlanForWrite();
        @Nullable SlotChange delta = InventoryPlanner.planAmountChange(basis.planned()[slot], slot, change, this::slotMaxStackSize);
        if (delta == null) {
            return EMPTY_COMMITTED;
        }
        return this.commitScoped(reason, basis, List.of(delta));
    }

    /**
     * 请求增减指定槽位的数量, 以 {@link UpdateReason.Program} 的名义.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param change 数量变化, 正数为增加, 负数为减少
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     * @see #tryChangeAmount(UpdateReason, int, int)
     */
    @NotNull
    public TransactionResult tryChangeAmount(int slot, int change) {
        return this.tryChangeAmount(UpdateReason.Program.INSTANCE, slot, change);
    }

    /**
     * 按 ADD 遍历顺序把物品尽量放进 Inventory, 先合并相似物品堆, 再占用空槽.
     * 整个放入过程作为一次事务提交.
     * <p>Pre 可以取消或编辑候选结果, 规划基准失效时返回冲突结果.
     *
     * @param reason 本次修改的原因
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     */
    @NotNull
    public AddResult tryAdd(@NotNull UpdateReason reason, @NotNull ItemStack item) {
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
                delta -> this.allowsAccess(reason, null, delta)
        );
        if (plan.deltas().isEmpty()) {
            return new AddResult(EMPTY_COMMITTED, plan.remaining());
        }
        // 整组槽位变更一次提交, 没提交成功视为一个都没放进去
        TransactionResult result = InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, plan.deltas())), false);
        return new AddResult(result, result instanceof TransactionResult.Committed ? plan.remaining() : input.getAmount());
    }

    /**
     * 请求把物品尽量放入库存, 以 {@link UpdateReason.Program} 的名义.
     *
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     * @see #tryAdd(UpdateReason, ItemStack)
     */
    @NotNull
    public AddResult tryAdd(@NotNull ItemStack item) {
        return this.tryAdd(UpdateReason.Program.INSTANCE, item);
    }

    /**
     * 按 COLLECT 遍历顺序收集与 template 相似的物品, 最多收集 {@code upTo} 个.
     * 整个收集过程作为一次事务提交.
     * <p>Pre 可以取消或编辑候选结果, 规划基准失效时返回冲突结果.
     *
     * @param reason 本次修改的原因
     * @param template 物品样板, 只参与相似判断
     * @param upTo 最多收集的数量
     * @return 收集结果, 包含规划收集数量; 取消或冲突时为 0
     */
    @NotNull
    public CollectResult tryCollect(@NotNull UpdateReason reason, @NotNull ItemStack template, int upTo) {
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
                delta -> this.allowsAccess(reason, null, delta),
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
     * 请求收集与样板相似的物品, 以 {@link UpdateReason.Program} 的名义.
     *
     * @param template 物品样板, 只参与相似判断
     * @param upTo 最多收集的数量
     * @return 收集结果, 包含规划收集数量; 取消或冲突时为 0
     * @see #tryCollect(UpdateReason, ItemStack, int)
     */
    @NotNull
    public CollectResult tryCollect(@NotNull ItemStack template, int upTo) {
        return this.tryCollect(UpdateReason.Program.INSTANCE, template, upTo);
    }

    /**
     * 按 OTHER 遍历顺序移除 matcher 选中的物品, 最多移除 {@code upTo} 个, 整个移除过程作为一次事务提交.
     * <p><strong>matcher 拿到内部只读引用, 不得修改或持有</strong>.
     * <p>Pre 可以取消或编辑候选结果, 规划基准失效时返回冲突结果.
     *
     * @param reason 本次修改的原因
     * @param matcher 判断某个物品是否应被移除的函数
     * @param upTo 最多移除的数量
     * @return 移除结果, 包含规划移除数量; 取消或冲突时为 0
     */
    @NotNull
    public RemoveResult tryRemove(@NotNull UpdateReason reason, @NotNull Predicate<@NotNull ItemStack> matcher, int upTo) {
        if (upTo <= 0) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        PlannedRoot basis = this.openPlanForWrite();
        // 在规划内容上计算要动哪些槽, matcher 由规划器在锁外逐个调用
        InventoryPlanner.TakePlan plan = InventoryPlanner.planRemove(basis.planned(), matcher, upTo, this.iterationOrder(OperationCategory.OTHER), delta -> this.allowsAccess(reason, null, delta));
        if (plan.deltas().isEmpty()) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        // 整组槽位变更一次提交, 没提交成功视为一个都没移除
        TransactionResult result = InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, plan.deltas())), false);
        return new RemoveResult(result, result instanceof TransactionResult.Committed ? plan.taken() : 0);
    }

    /**
     * 请求移除条件匹配的物品, 以 {@link UpdateReason.Program} 的名义.
     *
     * @param matcher 判断某个物品是否应被移除的函数
     * @param upTo 最多移除的数量
     * @return 移除结果, 包含规划移除数量; 取消或冲突时为 0
     * @see #tryRemove(UpdateReason, Predicate, int)
     */
    @NotNull
    public RemoveResult tryRemove(@NotNull Predicate<@NotNull ItemStack> matcher, int upTo) {
        return this.tryRemove(UpdateReason.Program.INSTANCE, matcher, upTo);
    }

    /**
     * 清空全部槽位, 整个清除过程作为一次事务提交.
     * <p>Pre 可以取消或编辑候选结果, 规划基准失效时返回冲突结果.
     *
     * @param reason 本次修改的原因
     * @return 事务结果
     */
    @NotNull
    public TransactionResult tryClear(@NotNull UpdateReason reason) {
        PlannedRoot basis = this.openPlanForWrite();
        @Nullable ItemStack[] planned = basis.planned();
        // 只给非空槽位生成变更
        List<SlotChange> deltas = new ArrayList<>(planned.length);
        for (int slot = 0; slot < planned.length; slot++) {
            if (planned[slot] != null) {
                deltas.add(new SlotChange(slot, planned[slot], null));
            }
        }
        if (deltas.isEmpty()) {
            return EMPTY_COMMITTED;
        }
        return this.commitScoped(reason, basis, deltas);
    }

    /**
     * 请求清空全部槽位, 以 {@link UpdateReason.Program} 的名义.
     *
     * @return 事务结果
     * @see #tryClear(UpdateReason)
     */
    @NotNull
    public TransactionResult tryClear() {
        return this.tryClear(UpdateReason.Program.INSTANCE);
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
     * <p>只检查现有内容, 不调用 AccessRule 或 Pre.
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
     * 按容量试算放入后的剩余数量, 不调用 AccessRule 或 Pre.
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
                .planAdd(this.openPlan().planned(), input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize, null)
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
        return this.simulateAdd(items, null);
    }

    // 同一快照连续推演, 有来源时按请求规则筛选候选.
    private int[] simulateAdd(List<? extends ItemStack> items, @Nullable UpdateReason reason) {
        @Nullable ItemStack[] working = this.openPlan().planned().clone();
        int[] remaining = new int[items.size()];
        int index = 0;
        for (int i = 0; i < items.size(); i++) {
            @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(items.get(i)));
            if (input == null) {
                index++;
                continue;
            }
            InventoryPlanner.AddPlan plan = InventoryPlanner
                    .planAdd(working, input, this.iterationOrder(OperationCategory.ADD), this::slotMaxStackSize,
                            reason == null ? null : delta -> this.allowsAccess(reason, null, delta));
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
     * 按现有内容试算可收集数量, 不调用 AccessRule 或 Pre.
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
     * 按请求来源与 AccessRule 试算放入, 不派发 Pre 或写入内容.
     *
     * @param reason 请求来源
     * @param item 要试算的物品
     * @return 预计放不下的数量, 不承诺后续请求能够提交
     */
    public int simulateTryAdd(@NotNull UpdateReason reason, @NotNull ItemStack item) {
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return 0;
        }
        if (reason instanceof PlayerUpdateReason && this.frozen()) {
            return input.getAmount();
        }
        return InventoryPlanner.planAdd(this.openPlan().planned(), input, this.iterationOrder(OperationCategory.ADD),
                this::slotMaxStackSize, delta -> this.allowsAccess(reason, null, delta)).remaining();
    }

    /**
     * 按 Program 来源试算请求放入.
     *
     * @param item 要试算的物品
     * @return 预计放不下的数量
     */
    public int simulateTryAdd(@NotNull ItemStack item) {
        return this.simulateTryAdd(UpdateReason.Program.INSTANCE, item);
    }

    /**
     * 按同一请求来源连续试算列表中的放入, 共享一份规划内容.
     *
     * @param reason 请求来源
     * @param items 按顺序试算的物品
     * @return 对应每个输入的剩余数量, 不派发 Pre
     */
    public int[] simulateTryAdd(@NotNull UpdateReason reason, @NotNull List<? extends ItemStack> items) {
        if (reason instanceof PlayerUpdateReason && this.frozen()) {
            int[] remaining = new int[items.size()];
            for (int i = 0; i < items.size(); i++) {
                remaining[i] = ItemUtils.amountOf(ItemUtils.nullIfEmpty(items.get(i)));
            }
            return remaining;
        }
        return this.simulateAdd(items, reason);
    }

    /**
     * 按 Program 来源连续试算请求放入.
     *
     * @param items 按顺序试算的物品
     * @return 对应每个输入的剩余数量
     */
    public int[] simulateTryAdd(@NotNull List<? extends ItemStack> items) {
        return this.simulateTryAdd(UpdateReason.Program.INSTANCE, items);
    }

    /**
     * 按指定来源连续试算请求放入.
     *
     * @param reason 请求来源
     * @param items 按顺序试算的物品
     * @return 对应每个输入的剩余数量
     */
    public int[] simulateTryAdd(@NotNull UpdateReason reason, ItemStack @NotNull ... items) {
        return this.simulateTryAdd(reason, Arrays.asList(items));
    }

    /**
     * 按 Program 来源连续试算请求放入.
     *
     * @param items 按顺序试算的物品
     * @return 对应每个输入的剩余数量
     */
    public int[] simulateTryAdd(ItemStack @NotNull ... items) {
        return this.simulateTryAdd(UpdateReason.Program.INSTANCE, Arrays.asList(items));
    }

    /**
     * 按请求来源与 AccessRule 试算收集, 不派发 Pre 或写入内容.
     *
     * @param reason 请求来源
     * @param template 用于相似判断的样板
     * @param upTo 最多收集的数量
     * @return 预计可收集数量, 不承诺后续请求能够提交
     */
    public int simulateTryCollect(@NotNull UpdateReason reason, @NotNull ItemStack template, int upTo) {
        @Nullable ItemStack sample = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(template));
        if (sample == null || upTo <= 0 || (reason instanceof PlayerUpdateReason && this.frozen())) {
            return 0;
        }
        return InventoryPlanner.planCollect(this.openPlan().planned(), sample, upTo, this.iterationOrder(OperationCategory.COLLECT),
                delta -> this.allowsAccess(reason, null, delta), this::slotMaxStackSize).taken();
    }

    /**
     * 按 Program 来源试算请求收集.
     *
     * @param template 用于相似判断的样板
     * @param upTo 最多收集的数量
     * @return 预计可收集数量
     */
    public int simulateTryCollect(@NotNull ItemStack template, int upTo) {
        return this.simulateTryCollect(UpdateReason.Program.INSTANCE, template, upTo);
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

    // 请求提交和权威命令共用同一把写锁; 引用存储由所属线程串行访问, 返回 null.
    @Nullable
    @ApiStatus.Internal
    public PlannedRoot.StateLock stateLock() {
        return new PlannedRoot.StateLock(this.writeLock, this.lockOrder);
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
        @Nullable
        protected StateLock stateLock() {
            return this.inventory().stateLock();
        }

        @Override
        public boolean isStale() {
            return this.inventory().state != this.planned();
        }

        @Override
        protected @Nullable ItemStack @NotNull [] buildNextState(@NotNull List<SlotChange> deltas) {
            // planned 与当前状态是同一个数组, 克隆它即克隆当前状态. 请求路径靠同一临界区内刚通过的
            // isStale 保证, 权威路径靠从 openPlan 起就持有写锁保证.
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
