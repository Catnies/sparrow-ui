package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.inventory.event.InventoryBundleSelectEvent;
import net.momirealms.sparrow.ui.inventory.event.SparrowInventoryClickEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.CollectResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryFactory;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * SparrowUI 所有受事务保护的Inventory的公共抽象, 可以把它理解成一个会自动通知变更的箱子.
 * <p>所有实现都遵守三条约定:
 * <ul>
 *   <li>空槽只用 {@code null} 表示, Inventory 中不会保留 AIR 物品或数量不大于 0 的物品;</li>
 *   <li>除名称以 {@code unsafe} 开头的方法外, 读出的物品都是调用方拥有的内容副本, 修改返回值不会影响 Inventory;</li>
 *   <li>每次修改都走完整的规划, 询问, 提交和通知流程, 事件以整次修改为单位派发.</li>
 * </ul>
 * <p>按内容放在哪里, 实现分成两种. {@link VirtualInventory} 这一种自己拿着内部状态数组, 参与事务加锁,
 * 并发校验和状态交换, 读操作读的是当前内部状态版本, 任何线程都可以安全调用.
 * {@link ReferencingInventory} 这一种内容放在外部存储里, 读写都直接落到那个存储, 并发校验改看 modCount,
 * 既不加锁也不交换状态, 访问是否串行由调用方负责.
 * 两种在写操作遇到并发冲突时都返回 {@link TransactionResult.Conflicted} 且不产生修改.
 * <p><strong>自己拿着状态数组的那一种, 无锁读与并发校验都建立在"内部状态数组的元素一经发布就不再被修改"之上</strong>:
 * 提交只换数组不改元素, 因此比对数组引用就足以发现并发写入. 为了对齐原版的对象身份行为,
 * 玩家把光标物品整堆交换进槽位时, 落进内部状态的就是菜单光标那一个实例, 于是该槽的元素可能与玩家光标同源 ——
 * 外部经 {@code HumanEntity#getItemOnCursor()} 之类拿到的活视图指向同一个底层物品, 对它就地改写数量或组件会绕过事务:
 * 数组引用没变, 并发校验看不见, 事件与 Window 同步也不会被触发. 这是与原版指针转移语义对齐的必然代价;
 * 需要改动物品的一方必须造新对象, 而不是就地写.
 * 内容放在外部存储的那一种没有这条限制: 它靠内容比对发现变更, 外部把存储里的物品就地改了数量, 组件或 PDC,
 * 都会在下一次比对时被发现, 以 {@link UpdateReason.External} 原因派发 post 事件并同步显示.
 * <p>事务事件使用被订阅 Inventory 自己的槽位编号, 一笔事务对一个订阅最多通知一次.
 * <p>Window 交互事件只属于被 InventoryLink 直接连接的 SparrowInventory 实例.
 */
public abstract class SparrowInventory {
    public static final int DEFAULT_MAX_STACK_SIZE = 99; // 槽位默认的堆叠上限
    public static final int ALL_SLOTS = -1; // 视觉映射变更通知中表示"全部槽位"的载荷
    static final TransactionResult.Committed EMPTY_COMMITTED = new TransactionResult.Committed(List.of()); // 无变更操作共享的成功结果: 变更列表为空, 也不派发事件
    private static final AtomicLong LOCK_ORDER_SOURCE = new AtomicLong(); // 锁序号发号器, 每创建一个 Inventory 发一个号

    private final long lockOrder = LOCK_ORDER_SOURCE.getAndIncrement(); // 跨 Inventory 事务按这个序号决定加锁先后
    private final ReentrantLock writeLock = new ReentrantLock();        // 只用来串行化写操作, 临界区内全是纯内存操作
    private final SlotOrder naturalOrder;                               // 遍历顺序的缺省回退, 构造时按槽位数建一次

    private volatile @Nullable ItemStack @NotNull [] state; // 当前内部状态版本, 数组和物品均归 Inventory 内部所有
    @Nullable private volatile Predicate<ItemStack> placementRule; // 容器全局物品放入规则, null 表示放行
    private volatile @Nullable Predicate<ItemStack> @NotNull [] placementRulesBySlot; // 容器槽位的物品放入规则, 非 null 时覆盖全局规则

    @Nullable private volatile Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer; // 容器全局视觉映射, 逐槽映射放行时使用
    @Nullable private volatile Function<@Nullable ItemStack, @Nullable ItemProvider> @NotNull [] visualizersBySlot; // 容器逐槽视觉映射, 层级最高
    @Nullable private volatile ItemProvider background; // 空槽占位背景, 独立于视觉映射的最底层

    // 三类操作各自挑选目标Inventory时用的优先级, 属于弱一致的配置; 没有设置过时是 0.
    private volatile int addGuiPriority;
    private volatile int collectGuiPriority;
    private volatile int otherGuiPriority;
    private volatile boolean includeObscuredSlots; // 未被 GUI 展示的槽位是否参与快速转移与双击收集, 属于弱一致的配置
    private volatile boolean frozen; // 玩家侧只读: 玩家经窗口的点击与拖拽一律不成立, 程序写入与外部同步不受影响, 属于弱一致的配置

    private final ObservableDispatcher<SparrowInventoryClickEvent> clickEvents = new ObservableDispatcher<>();
    private final ObservableDispatcher<InventoryBundleSelectEvent> bundleSelectEvents = new ObservableDispatcher<>();
    private final ObservableDispatcher<Integer> visualInvalidations = new ObservableDispatcher<>(); // 视觉映射变更通知, 载荷为受影响槽位, ALL_SLOTS 表示全部
    @Nullable private volatile InventoryUpdateChannel updateChannel;   // 第一次订阅事务更新时创建
    // 懒加载的 Bukkit 包装实例, 同一 Inventory 恒为同一个实例.
    @Nullable private volatile org.bukkit.inventory.Inventory bukkitView;

    /**
     * 以给定数组为初始内容创建 Inventory.
     *
     * @param initial 初始槽位内容, 空槽位置为 {@code null}.
     */
    SparrowInventory(@Nullable ItemStack @NotNull [] initial) {
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
        @SuppressWarnings("unchecked")
        @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider>[] visualizersBySlot = (Function<@Nullable ItemStack, @Nullable ItemProvider>[]) new Function<?, ?>[initial.length];
        this.visualizersBySlot = visualizersBySlot;
    }

    /**
     * 返回槽位数量, 创建后固定不变.
     *
     * @return 槽位数量
     */
    public int size() {
        return this.state.length;
    }

    /**
     * 一次性读出全部槽位, 得到当前时刻的独立副本.
     *
     * @return 按槽号排列的物品副本数组, 空槽位置为 {@code null}
     */
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
     * 零物品拷贝地读出全部槽位, 直接返回当前内部状态数组.
     * <p>调用方只能在当前调用栈内只读数组及其中的物品, 不得修改或保存这些引用. 违反约定会绕过事务,
     * 事件, Window 刷新和外部容器同步, 并可能破坏并发冲突检测.
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
     * @return 该类别使用的遍历顺序
     */
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        return this.naturalOrder;
    }

    /**
     * 把槽位换算成 SlotKey.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 该槽的 SlotKey
     */
    @NotNull
    SlotKey physicalKey(int slot) {
        return new SlotKey(this, slot);
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

    /**
     * 捕获当前 Inventory 的放入规则, 返回本次规划使用的槽位过滤器.
     * <p>{@code item} 会被零拷贝地交给规则, 并在整个过滤器生命周期内跨槽位复用同一个实例;
     * 调用方与规则都不得修改它.
     */
    @NotNull
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
     * 设置容器全局视觉映射. 映射函数接收槽位当前真实内容(空槽为 {@code null}),
     * 返回该槽展示用的 {@link ItemProvider}; 返回 {@code null} 表示放行, 交给下一层:
     * 非空槽按真实内容显示, 空槽依次回退 {@link #setBackground(ItemProvider) 容器背景} 和 GUI 背景.
     * <p>视觉配置是嵌套的层级: 逐槽映射在全局映射之上, 容器背景在最底层, 三者互不覆盖.
     * <p>映射只改变 Window 中的展示结果, 不影响真实内容, 事务与点击语义.
     * 设置后立即通知所有连接的显示端重新渲染; 同一映射可能被多个 Window 在各自线程并发调用, 应保持无状态或线程安全.
     * 映射抛出的异常会传播到渲染层, 由 Window 上报并保留该槽上次显示的内容.
     *
     * @param visualizer 新的全局视觉映射, {@code null} 表示不参与这一层
     */
    public void setVisualizer(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer) {
        this.visualizer = visualizer;
        this.visualInvalidations.publish(ALL_SLOTS);
    }

    /**
     * 返回当前的全局视觉映射.
     *
     * @return 全局视觉映射; 没有设置过时为 {@code null}, 表示按真实内容显示
     */
    @Nullable
    public Function<@Nullable ItemStack, @Nullable ItemProvider> getVisualizer() {
        return this.visualizer;
    }

    /**
     * 替换一个槽位的逐槽视觉映射, 它是该槽层级最高的一层:
     * 返回非 {@code null} 结果直接采用, 返回 {@code null} 表示放行, 继续询问全局映射.
     * 传入 {@code null} 会移除这一层, 使该槽直接从全局映射开始.
     * <p>映射的输入输出约定与 {@link #setVisualizer(Function)} 相同.
     *
     * @param slot 槽位序号
     * @param visualizer 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public void setVisualizer(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer) {
        Objects.checkIndex(slot, this.size());
        @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider>[] visualizersBySlot = this.visualizersBySlot.clone();
        visualizersBySlot[slot] = visualizer;
        this.visualizersBySlot = visualizersBySlot;
        this.visualInvalidations.publish(slot);
    }

    /**
     * 返回某个槽位的显式视觉映射; 不含回退到的全局映射.
     *
     * @param slot 槽位序号
     * @return 该槽的逐槽视觉映射; 没有覆盖时为 {@code null}, 表示这个槽用的是全局映射
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    public Function<@Nullable ItemStack, @Nullable ItemProvider> getVisualizer(int slot) {
        Objects.checkIndex(slot, this.size());
        return this.visualizersBySlot[slot];
    }

    /**
     * 给所有空槽设置占位背景. 它是视觉层级的最底层, 独立于视觉映射:
     * 只有空槽在逐槽与全局映射都放行后才显示背景, 设置映射不会覆盖背景, 反之亦然.
     * <p>空槽没有背景或背景也缺席时, 继续回退 GUI 背景.
     *
     * @param background 空槽占位背景, {@code null} 表示清除背景
     */
    public void setBackground(@Nullable ItemProvider background) {
        this.background = background;
        this.visualInvalidations.publish(ALL_SLOTS);
    }

    /**
     * 使用 ItemStack 给所有空槽设置占位背景.
     *
     * @param background 空槽占位背景
     */
    public void setBackground(@NotNull ItemStack background) {
        this.setBackground(ItemProvider.constant(background));
    }

    /**
     * 返回当前的空槽占位背景.
     *
     * @return 空槽占位背景; 没有设置过时为 {@code null}
     */
    @Nullable
    public ItemProvider getBackground() {
        return this.background;
    }

    /**
     * 返回一个槽位生效的视觉层级结果, 从高到低逐层询问, 上层放行才轮到下层:
     * 逐槽映射, 全局映射, 空槽的占位背景.
     * <p>{@code actual} 由调用方提供, 应当是该槽当前内容的副本; 渲染层用它避免重复读取.
     *
     * @param slot 槽位序号
     * @param actual 该槽当前真实内容, 空槽为 {@code null}
     * @return 展示用的提供器; 所有层都缺席或放行时为 {@code null}, 表示按真实内容显示
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    public ItemProvider visualize(int slot, @Nullable ItemStack actual) {
        @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> slotVisualizer = this.visualizersBySlot[slot];
        if (slotVisualizer != null) {
            ItemProvider mapped = slotVisualizer.apply(actual);
            if (mapped != null) {
                return mapped;
            }
        }
        @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> globalVisualizer = this.visualizer;
        if (globalVisualizer != null) {
            ItemProvider mapped = globalVisualizer.apply(actual);
            if (mapped != null) {
                return mapped;
            }
        }
        // 背景只垫在空槽下面, 非空槽由渲染层按真实内容显示
        return actual == null ? this.background : null;
    }

    /**
     * 返回指定类别的操作挑选目标 Inventory 时使用的优先级, 数值越大越优先.
     *
     * @param category 操作类别
     * @return 该类别的优先级
     */
    public int guiPriority(@NotNull OperationCategory category) {
        return switch (category) {
            case ADD -> this.addGuiPriority;
            case COLLECT -> this.collectGuiPriority;
            case OTHER -> this.otherGuiPriority;
        };
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
     * 把指定类别的优先级恢复成默认的 0.
     *
     * @param category 操作类别
     */
    public void clearGuiPriority(@NotNull OperationCategory category) {
        switch (category) {
            case ADD -> this.addGuiPriority = 0;
            case COLLECT -> this.collectGuiPriority = 0;
            case OTHER -> this.otherGuiPriority = 0;
        }
    }

    /**
     * 一次把全部三个类别的优先级恢复成默认的 0.
     */
    public void clearGuiPriority() {
        this.addGuiPriority = 0;
        this.collectGuiPriority = 0;
        this.otherGuiPriority = 0;
    }

    /**
     * 返回未被 GUI 展示的槽位是否参与快速转移与双击收集.
     *
     * @return 未展示槽位是否参与点击语义
     */
    public boolean includeObscuredSlots() {
        return this.includeObscuredSlots;
    }

    /**
     * 设置未被 GUI 展示的槽位是否参与快速转移与双击收集.
     * 默认不参与: 点击语义只触及本 Inventory 经未冻结槽位展示的部分.
     * 开启后未展示的槽位也会参与, 但 GUI 冻结槽展示的槽位始终不参与.
     *
     * @param includeObscuredSlots 未展示槽位是否参与点击语义
     */
    public void includeObscuredSlots(boolean includeObscuredSlots) {
        this.includeObscuredSlots = includeObscuredSlots;
    }

    /**
     * 返回本 Inventory 是否处于玩家侧只读状态.
     *
     * @return 是否玩家侧只读
     */
    public boolean frozen() {
        return this.frozen;
    }

    /**
     * 设置本 Inventory 是否玩家侧只读.
     * 冻结后玩家经任何窗口对本 Inventory 的点击与拖拽一律不成立: 不算候选, 不派发任何事件,
     * 也不作为快速转移与双击收集的来源或目标, 客户端预测会被纠正回来.
     * 程序写入与外部同步不受影响, 对应的事件照常派发.
     *
     * @param frozen 是否玩家侧只读
     */
    public void frozen(boolean frozen) {
        this.frozen = frozen;
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
     * <p>返回值是 Inventory 内部持有的实例. 调用方只能在当前调用栈内读取, 不得修改或保存引用;
     * 违反约定会绕过事务, 事件, Window 刷新和外部容器同步.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 槽内的内部物品引用, 空槽为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    public ItemStack unsafeItemAt(int slot) {
        // 先把 volatile 引用抓到局部变量
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
     * 零物品拷贝地判断是否存在 matcher 选中的物品.
     * <p>matcher 会直接收到内部 {@link ItemStack} 实例. matcher 只能读取当前入参, 不得修改或保存引用;
     * 违反约定会绕过事务, 事件, Window 刷新和外部容器同步.
     *
     * @param matcher 判断物品是否符合条件的只读函数
     * @return 至少有一个物品符合条件时返回 {@code true}
     */
    public boolean unsafeContains(@NotNull Predicate<? super ItemStack> matcher) {
        ItemStack[] snapshot = this.unsafeSnapshot();
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
     * 零物品拷贝地统计 matcher 选中的物品堆数量, 不累加堆内物品数量.
     * <p>matcher 会直接收到内部 {@link ItemStack} 实例. matcher 只能读取当前入参, 不得修改或保存引用;
     * 违反约定会绕过事务, 事件, Window 刷新和外部容器同步.
     *
     * @param matcher 判断物品是否符合条件的只读函数
     * @return 符合条件的非空槽数量
     */
    public int unsafeCount(@NotNull Predicate<? super ItemStack> matcher) {
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
        // 越界检查先于空输入短路生效, 行为不随物品内容摇摆
        Objects.checkIndex(slot, this.size());
        @Nullable ItemStack input = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(item));
        if (input == null) {
            return new AddResult(EMPTY_COMMITTED, 0);
        }
        PlannedRoot basis = this.openPlanForWrite();
        @Nullable ItemStack[] planned = basis.planned();
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
        TransactionResult result = this.commitScoped(reason, basis, List.of(new SlotChange(slot, current, after)));
        return new AddResult(result, result instanceof TransactionResult.Committed ? amount - moved : amount);
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
        // modifier 收到物品副本并在锁外执行; SlotChange 会再次复制返回值, 并把空物品转为 null
        @Nullable ItemStack modified = modifier.apply(ItemUtils.copyOrNull(planned[slot]));
        return this.commitScoped(reason, basis, List.of(new SlotChange(slot, planned[slot], modified)));
    }

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
    public TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change) {
        Objects.checkIndex(slot, this.size());
        PlannedRoot basis = this.openPlanForWrite();
        @Nullable ItemStack[] planned = basis.planned();
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
        return this.commitScoped(reason, basis, List.of(new SlotChange(slot, current, after)));
    }

    /**
     * 把当前 Inventory 上已规划好的变更作为单 Inventory 事务提交.
     *
     * @param reason 变更原因
     * @param basis 本次规划读到的基准, 提交时用它做并发校验
     * @param deltas 槽位变更
     * @return 事务结果
     */
    private TransactionResult commitScoped(UpdateReason reason, PlannedRoot basis, List<SlotChange> deltas) {
        return InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, deltas)), false);
    }

    /**
     * 按 ADD 遍历顺序把物品尽量放进 Inventory , 先合并相似物品堆, 再占用空槽.
     * 整个放入过程作为一次事务提交.
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
        // 在规划内容上计算: 先合并相似的未满堆, 再占空槽
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
        // 整组槽位变更一次提交; 没提交成功视为一个都没放进去
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
        // 在规划内容上计算: 先收未满堆, 不够再收满堆
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
        // 整组槽位变更一次提交; 没提交成功视为一个都没收到
        TransactionResult result = InventoryTransactions.commit(reason, List.of(new TransactionScope(basis, plan.deltas())), false);
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
        PlannedRoot basis = this.openPlanForWrite();
        // 在规划内容上计算要动哪些槽; matcher 由规划器在锁外逐个调用
        InventoryPlanner.TakePlan plan = InventoryPlanner.planRemove(basis.planned(), matcher, upTo, this.iterationOrder(OperationCategory.OTHER));
        if (plan.deltas().isEmpty()) {
            return new RemoveResult(EMPTY_COMMITTED, 0);
        }
        // 整组槽位变更一次提交; 没提交成功视为一个都没移除
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
     * <p>与 {@link #mayPlace(ItemStack)} 相对, {@code item} 既参与相似判断, 也提供需要取出的数量;
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
     * <p>多个物品共用同一份规划内容: 前面已经算作取走的部分, 后面不会再认领一次.
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
                working[delta.slot()] = delta.after();
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
        return InventoryPlanner.planCollect(this.openPlan().planned(), sample, upTo, this.iterationOrder(OperationCategory.COLLECT), null, this::slotMaxStackSize).taken();
    }

    /**
     * 让 ReferencingInventory 同步最新内容.
     * 自己持有数据的 Inventory 调用它没有效果;
     * ReferencingInventory 的调用方必须保证当前线程可以访问外部容器;
     * 平台拒绝访问时异常会直接传播.
     */
    public void refresh() {
    }

    /**
     * 为一次写规划做准备, 触发写前同步.
     * 任何写入口在读取规划内容之前都会经过这里, simulate 等纯读路径不会触发.
     */
    void prepareWrite() {
    }

    /**
     * 把 SparrowInventory 包装成原生 CraftInventory, 同一个 Inventory 永远返回同一个包装实例.
     * CraftInventory 背后的 NMS Container 直接代理本 Inventory, 槽位写入会走 Sparrow 的事务流程.
     * 与 Bukkit 容器绑定的信息(观看者, 持有者, 位置)一律为 "Null", 类型固定为 CHEST.
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
     * 判断当前 Inventory 有没有点击订阅者, 供派发方在无人监听时跳过事件构造.
     */
    boolean hasClickObservers() {
        return this.clickEvents.subscriptionCount() != 0;
    }

    /**
     * 订阅玩家点击本 Inventory 连接槽的事件.
     * 事件在候选形成后、事务 Pre 前派发, 取消会阻止候选提交.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    public Subscription subscribeClick(@NotNull Observer<? super SparrowInventoryClickEvent> observer) {
        return this.clickEvents.subscribe(observer);
    }

    /**
     * 向当前 Inventory 的观察者派发一次点击.
     */
    void publishClick(@NotNull SparrowInventoryClickEvent event) {
        this.clickEvents.publish(event);
    }

    /**
     * 判断当前 Inventory 有没有 Bundle 选择事件订阅者, 供派发方在无人监听时跳过事件构造.
     */
    boolean hasBundleSelectObservers() {
        return this.bundleSelectEvents.subscriptionCount() != 0;
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
     * 向当前 Inventory 的观察者派发一次 Bundle 选择.
     */
    void publishBundleSelect(@NotNull InventoryBundleSelectEvent event) {
        this.bundleSelectEvents.publish(event);
    }

    /**
     * 订阅事务提交前的事件, 处理器可以取消整个事务.
     * 一笔事务对本次订阅最多通知一次. {@link InventoryPreUpdateEvent#slotChanges()} 中的槽位编号属于当前 Inventory,
     * 当前 Inventory 没有槽位变更时不会通知.
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
     * {@link InventoryPostUpdateEvent#slotChanges()} 中的槽位编号属于当前 Inventory, 没有槽位变更时不会通知.
     * 连续修改同一个 Inventory 时, 事件顺序与事务提交顺序一致.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    public Subscription subscribePostUpdate(@NotNull Observer<? super InventoryPostUpdateEvent> observer) {
        return this.updateChannel().subscribePost(observer);
    }

    /**
     * 订阅视觉映射变更通知, 载荷为受影响的槽位序号, {@link #ALL_SLOTS} 表示全部槽位.
     * 显示端收到通知后应重新渲染对应槽位; 通知可能来自任意调用配置方法的线程.
     *
     * @param observer 通知处理器
     * @return 订阅凭证, 关闭后不再接收通知
     */
    @NotNull
    public Subscription subscribeVisualInvalidation(@NotNull Observer<? super Integer> observer) {
        return this.visualInvalidations.subscribe(observer);
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
                    channel = new InventoryUpdateChannel(this);
                    this.updateChannel = channel;
                }
            }
        }
        return channel;
    }

    /**
     * 返回当前 Inventory 已经创建的事务订阅器, 不触发创建.
     * <p>事务引擎用它找出本笔事务要通知谁: 从未订阅过的 Inventory 不需要为它建一个空订阅器.
     *
     * @return 事务订阅器; 从未订阅过时为 {@code null}
     */
    @Nullable
    InventoryUpdateChannel updateChannelIfPresent() {
        return this.updateChannel;
    }

    /**
     * 打开纯读用途的规划基准: 给 simulate 这类零副作用的路径使用.
     *
     * @return 本次规划读到的状态版本
     */
    @NotNull
    PlannedRoot openPlan() {
        return new PlannedRoot.Stm(this, this.currentState());
    }

    /**
     * 打开写路径的规划基准: 在读取规划内容之前先做一次写前准备.
     * (ReferencingInventory 在这个方法完成外部内容同步).
     *
     * @return 本次规划读到的状态版本
     */
    @NotNull
    PlannedRoot openPlanForWrite() {
        this.prepareWrite();
        return this.openPlan();
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
     * // todo 或许可以将2个不同的类分散出去, 或者将这个类单独分到一个文件
     * 一次规划读到的 Inventory 内容, 同时决定这个 Inventory 怎么参与事务: 加锁, 校验, 构造新状态,
     * 交换和落地这五步都由它自己给出做法, 事务引擎照着调用, 不用管面前是哪一种 Inventory.
     * <p>{@code planned} 数组只用来读内容(规划读取, 事件 before 采样, 解析搬运来源);
     * 规划依据还成不成立要问 {@link #isStale()} —— 两种实现的判断方式不一样, 调用方不要自己拿数组比对.
     */
    abstract static sealed class PlannedRoot {
        private final SparrowInventory inventory;
        private final @Nullable ItemStack @NotNull [] planned;

        PlannedRoot(@NotNull SparrowInventory inventory, @Nullable ItemStack @NotNull [] planned) {
            this.inventory = inventory;
            this.planned = planned;
        }

        @NotNull
        final SparrowInventory inventory() {
            return this.inventory;
        }

        final @Nullable ItemStack @NotNull [] planned() {
            return this.planned;
        }

        /**
         * 本基准参与提交临界区的方式: 需要加锁的返回锁凭证, 不加锁的返回 {@code null}.
         */
        @Nullable
        abstract StateLock stateLock();

        /**
         * 本基准是否已经失效. 提交临界区内的乐观校验与候选复核的 ROOT_STATE 检查共用本方法.
         */
        abstract boolean isStale();

        /**
         * 在提交临界区内构造应用变更后的新状态; 不需要交换状态的返回 {@code null}.
         * 只允许在 {@link #isStale()} 刚刚通过的同一临界区内调用.
         */
        abstract @Nullable ItemStack @Nullable [] buildNextState(@NotNull List<SlotChange> deltas);

        /**
         * 在提交临界区内把构造产物设为当前状态; 产物为 {@code null} 时无事发生.
         */
        abstract void swapTo(@Nullable ItemStack @Nullable [] nextState);

        /**
         * 状态提交后, post 事件派发前的落地动作. 是否调用由引擎按事务属性决定(External 同步免回写).
         *
         * @param deltas 本写集的槽位变更
         * @param transfers 整笔事务里认定为整堆搬运的物品, 内容放在外部存储的那一种据此转移 NMS 句柄
         */
        abstract void land(@NotNull List<SlotChange> deltas, @NotNull LiveTransfers transfers);

        /**
         * 全序加锁凭证: 事务引擎按 {@code order} 升序逐把加锁, 消除跨 Inventory 事务的死锁可能.
         */
        record StateLock(@NotNull ReentrantLock lock, long order) {
        }

        /**
         * 内容就在 Inventory 自己状态数组里时用的规划基准: planned 就是规划那一刻的状态数组本身,
         * 它同时也是并发校验的依据 —— 数组元素发布后不再修改, 换内容就是换数组, 比引用就能发现并发提交.
         */
        static final class Stm extends PlannedRoot {

            Stm(@NotNull SparrowInventory inventory, @Nullable ItemStack @NotNull [] planned) {
                super(inventory, planned);
            }

            @Override
            @NotNull
            StateLock stateLock() {
                return new StateLock(this.inventory().writeLock(), this.inventory().lockOrder());
            }

            @Override
            boolean isStale() {
                return this.inventory().currentState() != this.planned();
            }

            @Override
            @Nullable ItemStack @NotNull [] buildNextState(@NotNull List<SlotChange> deltas) {
                // isStale 刚在同一临界区内通过, planned 与当前状态是同一个数组, 克隆它即克隆当前状态.
                @Nullable ItemStack[] next = this.planned().clone();
                for (int i = 0; i < deltas.size(); i++) {
                    SlotChange delta = deltas.get(i);
                    next[delta.slot()] = delta.unsafeAfter();
                }
                return next;
            }

            @Override
            void swapTo(@Nullable ItemStack @Nullable [] nextState) {
                if (nextState != null) {
                    this.inventory().swapState(nextState);
                }
            }

            @Override
            void land(@NotNull List<SlotChange> deltas, @NotNull LiveTransfers transfers) {
                // 内容就在状态数组里, 上一步换过数组就已经落地了, 这里没有别的事情要做.
            }
        }

        /**
         * 内容放在外部存储里时用的规划基准: planned 是新建时逐槽读存储填出来的临时数组, 每次规划都重新建一份,
         * 只用来读内容; 并发校验改看新建时记下的 modCount —— 之后任何写入或吸收外部变更都会让它对不上.
         */
        static final class Live extends PlannedRoot {
            private final ReferencingInventory owner;
            private final long modCountAtPlan;

            Live(@NotNull ReferencingInventory owner, @Nullable ItemStack @NotNull [] planned, long modCountAtPlan) {
                super(owner, planned);
                this.owner = owner;
                this.modCountAtPlan = modCountAtPlan;
            }

            @Override
            @Nullable
            StateLock stateLock() {
                return null;
            }

            @Override
            boolean isStale() {
                return this.owner.liveModCount() != this.modCountAtPlan;
            }

            @Override
            @Nullable ItemStack @Nullable [] buildNextState(@NotNull List<SlotChange> deltas) {
                return null;
            }

            @Override
            void swapTo(@Nullable ItemStack @Nullable [] nextState) {
            }

            @Override
            void land(@NotNull List<SlotChange> deltas, @NotNull LiveTransfers transfers) {
                this.owner.liveApply(deltas, transfers);
            }
        }
    }
}
