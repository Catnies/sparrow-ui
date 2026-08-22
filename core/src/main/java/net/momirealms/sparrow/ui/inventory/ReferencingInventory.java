package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.inventory.storage.BukkitStorage;
import net.momirealms.sparrow.ui.inventory.storage.ExternalStorage;
import net.momirealms.sparrow.ui.inventory.storage.SlotKey;
import net.momirealms.sparrow.ui.inventory.transaction.InventoryTransactions;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 内容放在 {@link ExternalStorage} 里的 Inventory 实现, 读写直达存储, 自己不留一份内容.
 * <p><strong>串行访问由调用方负责.</strong> 所有读写都必须在存储可合法访问的上下文中串行执行
 * (Bukkit 存储即容器的属主线程); 接入 Window 期间, 玩家实体线程就是事实上的串行化线程.
 * 本类不判断平台或存储的执行所有者, 也不调度到其线程. 平台抛出的访问异常会沿调用栈传播.
 * <p>并发校验看 modCount 而不是状态数组: 规划基准是当场逐槽读存储填出来的临时数组, 每次规划都重建一份, 只用来读内容.
 * <p>外部世界绕过本类直接改存储时, 靠与 lastKnown 逐槽比对发现, 以
 * {@link net.momirealms.sparrow.ui.inventory.event.UpdateReason.External} 的名义补派 post 事件并同步显示.
 * 比对由 Window 每 tick 一次的 {@link #refresh()} 和写入口的写前准备触发, 本类自己不注册调度任务.
 */
public final class ReferencingInventory extends SparrowInventory {
    private final ExternalStorage storage;          // 内容实际存放的地方, 读写一律以它为准
    private final @Nullable Inventory referenced;   // 被引用的 Bukkit 容器, 引用的不是 Bukkit 容器时为 null
    private final SlotKey[] externalSlots;          // 当前 Inventory 槽位 -> 存储槽位, 读写与比对共用
    private final @Nullable SlotOrder addOrder;     // 玩家存储区的 ADD 顺序按原版 quick-move 反向遍历, 其余情况为 null

    private final @Nullable ItemStack[] lastKnown;  // 上次见到的内容, 逐槽一份副本, 只用来发现外部改动
    private volatile boolean retired;               // 存储已经不在了, 之后读到空, 写入一律失败
    private long modCount;                          // 并发校验用的计数, 自己写入或吸收外部变更后加一

    // 交给基类的状态数组只是拿来定槽位数量的: 内容读写全改道外部存储, 那个数组永远是空的, 也永远不会被交换.
    private ReferencingInventory(
            ExternalStorage storage,
            @Nullable Inventory referenced,
            @Nullable ItemStack[] initialKnown,
            SlotOrder slotMapping,
            @Nullable SlotOrder addOrder
    ) {
        super(new ItemStack[initialKnown.length]);
        this.storage = storage;
        this.referenced = referenced;
        this.externalSlots = externalSlots(storage, slotMapping);
        this.addOrder = addOrder;
        this.lastKnown = initialKnown;
    }

    /**
     * 引用容器的全部内容({@code getContents}).
     *
     * @param inventory 被引用的 Bukkit 容器
     * @return ReferencingInventory
     */
    @NotNull
    public static ReferencingInventory fromContents(@NotNull Inventory inventory) {
        return create(inventory, Inventory::getContents, UnaryOperator.identity(), false);
    }

    /**
     * 引用容器的存储内容({@code getStorageContents}, 不含盔甲与副手).
     *
     * @param inventory 被引用的 Bukkit 容器
     * @return ReferencingInventory
     */
    @NotNull
    public static ReferencingInventory fromStorageContents(@NotNull Inventory inventory) {
        return create(inventory, Inventory::getStorageContents, UnaryOperator.identity(), false);
    }

    /**
     * 引用玩家背包的存储内容, 并把热键行挪到当前 Inventory 的最后九个槽位:
     * 当前 Inventory 槽位 {@code i} 对应 Bukkit 容器槽位 {@code (i + 9) % 36}, 主背包在前, 快捷栏在后.
     * ADD 操作按原版 quick-move 的习惯从热键行尾部反向遍历.
     *
     * @param inventory 玩家背包
     * @return ReferencingInventory
     */
    @NotNull
    public static ReferencingInventory fromPlayerStorageContents(@NotNull PlayerInventory inventory) {
        return create(inventory, Inventory::getStorageContents, ReferencingInventory::reorderPlayerStorage, true);
    }

    /**
     * 引用任意外部存储, 当前 Inventory 槽位与存储槽位一一对应.
     *
     * @param storage 内容实际存放的外部存储
     * @return ReferencingInventory
     */
    @NotNull
    public static ReferencingInventory of(@NotNull ExternalStorage storage) {
        @Nullable ItemStack[] raw = storage.readAll();
        SlotOrder slotMapping = SlotOrder.of(identitySlots(raw.length));
        return new ReferencingInventory(storage, null, readLogicalContents(raw, slotMapping), slotMapping, null);
    }

    // 三个 fromXxx 工厂共用的装配.
    static ReferencingInventory create(
            Inventory inventory,
            Function<Inventory, @Nullable ItemStack[]> contentsGetter,
            UnaryOperator<int[]> slotReorder,
            boolean reverseAddOrder
    ) {
        // 包出存储
        ExternalStorage storage = BukkitStorage.of(inventory, contentsGetter);
        // 按重排函数定好槽位映射
        @Nullable ItemStack[] raw = storage.readAll();
        SlotOrder slotMapping = SlotOrder.of(slotReorder.apply(identitySlots(raw.length)));
        if (slotMapping.size() != raw.length) {
            throw new IllegalArgumentException("slot mapping size " + slotMapping.size() + " does not match contents size " + raw.length);
        }
        // 决定 ADD 要不要反向遍历
        @Nullable SlotOrder addOrder = reverseAddOrder ? SlotOrder.natural(raw.length).reversed() : null;
        return new ReferencingInventory(storage, inventory, readLogicalContents(raw, slotMapping), slotMapping, addOrder);
    }

    /**
     * 返回被引用的 Bukkit 容器, 内容不住在 Bukkit 容器里时返回 {@code null}.
     *
     * @return 被引用的容器
     */
    @Nullable
    public Inventory referencedInventory() {
        return this.referenced;
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接读取外部存储现值的副本.
     */
    @Override
    @Nullable
    public ItemStack itemAt(int slot) {
        return ItemUtils.copyOrNull(this.unsafeItemAt(slot));
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接读取外部存储现值; 存储给出活视图时返回的就是活视图.
     */
    @Override
    @Nullable
    public ItemStack unsafeItemAt(int slot) {
        if (this.retired) return null;
        return ItemUtils.nullIfEmpty(this.storage.read(this.externalSlots[slot].slot()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>逐槽读取外部存储现值的副本.
     */
    @Override
    public @Nullable ItemStack @NotNull [] snapshot() {
        @Nullable ItemStack[] copy = this.unsafeSnapshot();
        for (int slot = 0; slot < copy.length; slot++) {
            copy[slot] = ItemUtils.copyOrNull(copy[slot]);
        }
        return copy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>返回按当前 Inventory 槽位排列的存储现值视图数组; 数组每次调用新建, 元素契约与存储的读取契约相同.
     */
    @Override
    public @Nullable ItemStack @NotNull [] unsafeSnapshot() {
        return this.readView();
    }

    @Override
    public void refresh() {
        // 存储没了就地退役, 这一轮不再去读它
        if (this.retired) return;
        if (!this.storage.alive()) {
            this.retire();
            return;
        }
        this.reconcileFromStorage();
    }

    /**
     * 让本 Inventory 退役, 内容存放的地方已经不在了, 这个 Inventory 从此不再可用.
     * <p>退役之后读到的一律是空, 写入一律失败(在途的与新发起的事务都会以
     * {@link TransactionResult.Conflicted} 收场), 快速转移与双击收集也不再把它当成目标.
     * 展示它的 Window 会把那些槽位重新渲染成空.
     * <p>可以直接调用(例如在方块破坏事件里), 也可以交给 {@link ExternalStorage#alive()} 让每 tick 的
     * {@link #refresh()} 自己发现. 重复调用没有额外效果.
     */
    public void retire() {
        if (this.retired) {
            return;
        }
        this.retired = true;
        // 推一下 modCount 作废全部在途规划基准; 退役之后新建的基准也会被 Live.isStale 一律判失效.
        this.modCount++;
        this.visual().dirty();
        this.updateContentSignal();
    }

    @Override
    public boolean retired() {
        return this.retired;
    }

    /**
     * {@inheritDoc}
     *
     * <p>返回存储声明的堆叠上限.
     */
    @Override
    public int slotMaxStackSize(int slot) {
        return this.storage.maxStackSize(this.externalSlots[slot].slot());
    }

    /**
     * {@inheritDoc}
     *
     * <p>玩家存储区的 ADD 顺序走原版 quick-move 的反向顺序, 其余情况回退自然顺序.
     */
    @Override
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        return category == OperationCategory.ADD && this.addOrder != null
                ? this.addOrder
                : super.iterationOrder(category);
    }

    /**
     * {@inheritDoc}
     *
     * <p>返回外部存储给出的 SlotKey, 构造时逐槽算好,
     * 两个 ReferencingInventory 最终写同一格时, SlotKey 相同.
     */
    @Override
    @NotNull
    public SlotKey physicalKey(int slot) {
        return this.externalSlots[slot];
    }

    // 写之前先确认存储还在, 再比对一次把积压的外部变更派发出去, 接下来的规划才是基于最新内容算的.
    @Override
    @ApiStatus.Internal
    public void prepareWrite() {
        this.refresh();
    }

    // 逐槽读存储填出一个临时数组当规划内容, 并记下当前 modCount 作为日后判断失效的凭据.
    @Override
    @NotNull
    @ApiStatus.Internal
    public PlannedRoot openPlan() {
        return new Live(this, this.readView(), this.modCount);
    }

    // 把本写集落进外部存储, 顺带同步 lastKnown, 免得自己写的东西下一轮被当成外部改动.
    private void liveApply(@NotNull List<SlotChange> deltas) {
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            int externalSlot = this.externalSlots[delta.slot()].slot();
            // 现值已经和要写的内容相等就别动它: 一次等值覆盖会白白作废外部还拿着的那个引用
            if (!ItemUtils.isContentEqual(this.storage.read(externalSlot), delta.unsafeAfter())) {
                this.storage.write(externalSlot, delta.after());
            }
            this.lastKnown[delta.slot()] = delta.after();
        }
        this.modCount++;
    }

    // 存储内容和 lastKnown 逐槽比一遍, 对不上的就认下来并以 External 原因补派 post 事件; 只记账不回写, 存储里的实例原样不动.
    private void reconcileFromStorage() {
        if (this.retired) return;
        // 比较阶段直接用存储读出来的引用, 不复制物品 —— 绝大多数 tick 根本没有外部变更, 只有对不上的那一格才由 SlotChange 复制
        @Nullable ItemStack[] raw = this.storage.readAll();
        @Nullable List<SlotChange> deltas = null;
        for (int slot = 0; slot < this.lastKnown.length; slot++) {
            @Nullable ItemStack liveItem = raw[this.externalSlots[slot].slot()];
            @Nullable ItemStack knownItem = this.lastKnown[slot];
            if (!ItemUtils.isContentEqual(liveItem, knownItem)) {
                if (deltas == null) {
                    deltas = new ArrayList<>();
                }
                deltas.add(new SlotChange(slot, knownItem, liveItem));
            }
        }
        if (deltas == null) {
            return;
        }

        // 先记账再派发: lastKnown 改记现值(直接共用 SlotChange 里那份副本, 两边都不会去改它), modCount 加一,
        // 这样 post 处理器在事件里重新发起写入时, 看到的已经是新版本.
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            this.lastKnown[delta.slot()] = delta.unsafeAfter();
        }
        this.modCount++;
        TransactionResult result = InventoryTransactions.commitExternalSync(
                new TransactionScope(new Live(this, this.mapView(raw), this.modCount), deltas)
        );
        // 调用方既然保证了串行访问, 这里就不该冲突; 真冲突了说明调用边界已经被破坏, 交给统一异常处理器上报
        if (!(result instanceof TransactionResult.Committed)) {
            SparrowUI.getInstance().handleException(
                    "Failed to dispatch external changes of a ReferencingInventory",
                    new IllegalStateException("external dispatch was rejected: " + result)
            );
        }
    }

    // 读出存储全部内容并按当前槽位排列; 退役之后干脆不碰存储, 那个容器已经和服务端脱钩,
    // 里面剩下的东西既不该出现在菜单里, 也不该当规划依据, 所以直接给一整排空槽位.
    private @Nullable ItemStack @NotNull [] readView() {
        if (this.retired) {
            return new ItemStack[this.externalSlots.length];
        }
        return this.mapView(this.storage.readAll());
    }

    // 把存储原始内容按当前槽位顺序摆好, 元素零拷贝, 空物品折成 null.
    @Nullable
    private ItemStack @NotNull [] mapView(@Nullable ItemStack[] raw) {
        @Nullable ItemStack[] view = new ItemStack[this.externalSlots.length];
        for (int slot = 0; slot < view.length; slot++) {
            view[slot] = ItemUtils.nullIfEmpty(raw[this.externalSlots[slot].slot()]);
        }
        return view;
    }

    // 取一份内容副本当 lastKnown 的初值, 之后的外部变更都是拿它做基准比出来的.
    private static @Nullable ItemStack[] readLogicalContents(@Nullable ItemStack[] raw, SlotOrder slotMapping) {
        @Nullable ItemStack[] logical = new ItemStack[raw.length];
        for (int slot = 0; slot < raw.length; slot++) {
            logical[slot] = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(raw[slotMapping.slotAt(slot)]));
        }
        return logical;
    }

    // 生成 0 到 size-1 的恒等槽位数组, 交给重排函数加工.
    private static int[] identitySlots(int size) {
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return slots;
    }

    // 构造时一次算好每个槽位的 SlotKey, 之后判断"是不是同一格"就不必再问存储.
    private static SlotKey[] externalSlots(ExternalStorage storage, SlotOrder slotMapping) {
        SlotKey[] externalSlots = new SlotKey[slotMapping.size()];
        for (int slot = 0; slot < slotMapping.size(); slot++) {
            externalSlots[slot] = storage.keyOf(slotMapping.slotAt(slot));
        }
        return externalSlots;
    }

    // 玩家背包重排: 槽位 i 指向 Bukkit 容器槽位 (i + 9) % 36, 热键行(容器 0-8)因此落到本 Inventory 的 27-35, 也就是最后一行.
    private static int[] reorderPlayerStorage(int[] slots) {
        int[] reordered = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            reordered[i] = (slots[i] + 9) % 36;
        }
        return reordered;
    }

    // 内容放在外部存储里时用的规划基准: planned 是新建时逐槽读存储填出来的临时数组, 只用来读内容;
    // 校验改看新建时记下的 modCount —— 之后任何写入或吸收外部变更都会让它对不上.
    private static final class Live extends PlannedRoot {
        private final ReferencingInventory owner;
        private final long modCountAtPlan;

        private Live(@NotNull ReferencingInventory owner, @Nullable ItemStack @NotNull [] planned, long modCountAtPlan) {
            super(owner, planned);
            this.owner = owner;
            this.modCountAtPlan = modCountAtPlan;
        }

        @Override
        @Nullable
        protected StateLock stateLock() {
            return null;
        }

        @Override
        public boolean isStale() {
            // 已退役的 Inventory 没有任何基准还成立, 事务一律以 Conflicted 收场
            return this.owner.retired || this.owner.modCount != this.modCountAtPlan;
        }

        @Override
        protected @Nullable ItemStack @Nullable [] buildNextState(@NotNull List<SlotChange> deltas) {
            return null;
        }

        @Override
        protected void swapTo(@Nullable ItemStack @Nullable [] nextState) {
        }

        @Override
        protected void land(@NotNull List<SlotChange> deltas) {
            this.owner.liveApply(deltas);
        }
    }
}
