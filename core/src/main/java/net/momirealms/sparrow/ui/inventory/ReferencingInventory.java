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
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 将逻辑槽位映射到 {@link ExternalStorage}, 存储保有权威内容, 本对象只留一份外部变更比对基准.
 * <p><strong>调用方必须在存储所属线程串行访问</strong>. {@link #refresh()} 会吸收外部变更, 存储失效后本 Inventory 退役.
 * <p>无 try 的修改方法在这里不加锁, 读到旧值和写回新值之间没有任何保护, 所属线程这条约束就是它全部的串行来源.
 * 请求路径还有 modCount 乐观校验兜一层, 撞上并发会返回 {@link TransactionResult.Conflicted}; 权威路径连这层也没有.
 */
public final class ReferencingInventory extends SparrowInventory {
    private static final ExternalStorage RETIRED_STORAGE = new ExternalStorage() {
        @Override
        public int size() {
            return 0;
        }

        @Override
        @Nullable
        public ItemStack read(int slot) {
            return null;
        }

        @Override
        public void write(int slot, @Nullable ItemStack item) {
        }

        @Override
        public int maxStackSize(int slot) {
            return SparrowInventory.DEFAULT_MAX_STACK_SIZE;
        }

        @Override
        public boolean alive() {
            return false;
        }
    };

    private ExternalStorage storage; // 退役之后换成 RETIRED_STORAGE, 这样读写不用到处判断退役状态
    private @Nullable WeakReference<Inventory> referenced;
    private final int[] storageSlots;           // 逻辑槽位 -> 存储读写坐标
    private final SlotKey[] slotKeys;           // 逻辑槽位 -> 物理身份, 只用来判断两格是不是同一个真实位置, 不能拿它去读写
    private final @Nullable SlotOrder addOrder;

    private final @Nullable ItemStack[] lastKnown; // 上次看到的外部内容. 下次刷新时拿它一格一格比, 差异就是外面偷偷改掉的东西
    private volatile boolean retired;
    private long modCount;

    // 基类那个状态数组在这里只用来提供尺寸, 内容一律从外部存储实时读, 自己不留一份.
    private ReferencingInventory(
            ExternalStorage storage,
            @Nullable Inventory referenced,
            @Nullable ItemStack[] initialKnown,
            SlotOrder slotMapping,
            @Nullable SlotOrder addOrder
    ) {
        super(new ItemStack[initialKnown.length]);
        this.storage = storage;
        this.referenced = referenced == null ? null : new WeakReference<>(referenced);
        this.storageSlots = storageSlots(slotMapping);
        this.slotKeys = slotKeys(storage, slotMapping);
        this.addOrder = addOrder;
        this.lastKnown = initialKnown;
    }

    /**
     * ReferencingInventory 的全部内容({@code getContents}).
     *
     * @param inventory 被引用的 Bukkit 容器
     * @return ReferencingInventory
     */
    @NotNull
    public static ReferencingInventory fromContents(@NotNull Inventory inventory) {
        return create(inventory, Inventory::getContents, UnaryOperator.identity(), false);
    }

    /**
     * 引用坐骑的完整背包, 0 为鞍位, 1 为身体装备位, 从 2 开始为储物格.
     * <p>Spigot 的装备独立于 Bukkit 内容数组, 本入口将两部分组合为与 Paper 相同的槽位布局.
     *
     * @param mount 被引用的坐骑
     * @return 包含装备和储物格的引用背包
     */
    @NotNull
    public static ReferencingInventory fromMountContents(@NotNull AbstractHorse mount) {
        Inventory inventory = mount.getInventory();
        if (VersionHelper.hasPaperPatch) {
            return fromContents(inventory);
        }
        ExternalStorage storage = BukkitStorage.ofMount(mount, inventory);
        ItemStack[] contents = storage.readAll();
        SlotOrder slots = SlotOrder.of(identitySlots(contents.length));
        return new ReferencingInventory(storage, inventory, readLogicalContents(contents, slots), slots, null);
    }

    /**
     * ReferencingInventory 的存储内容({@code getStorageContents}, 不含盔甲与副手).
     *
     * @param inventory 被引用的 Bukkit 容器
     * @return ReferencingInventory
     */
    @NotNull
    public static ReferencingInventory fromStorageContents(@NotNull Inventory inventory) {
        return create(inventory, Inventory::getStorageContents, UnaryOperator.identity(), false);
    }

    /**
     * 引用玩家背包的存储内容, 并把热键行挪到当前 Inventory 的最后九个槽位.
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
     * 返回被引用的 Bukkit 容器, 内容不住在 Bukkit 容器里或者本 Inventory 已退役时返回 {@code null}.
     *
     * @return 被引用的容器
     */
    @Nullable
    public Inventory referencedInventory() {
        return this.referenced == null ? null : this.referenced.get();
    }

    /**
     * {@inheritDoc}
     *
     * <p>返回外部存储现值的副本.
     */
    @Override
    @Nullable
    public ItemStack itemAt(int slot) {
        return ItemUtils.copyOrNull(this.unsafeItemAt(slot));
    }

    /**
     * {@inheritDoc}
     *
     * <p>存储返回活视图时, 本方法也返回该活视图.
     */
    @Override
    @Nullable
    public ItemStack unsafeItemAt(int slot) {
        if (this.retired) return null;
        return ItemUtils.nullIfEmpty(this.storage.read(this.storageSlots[slot]));
    }

    /**
     * {@inheritDoc}
     *
     * <p>逐槽返回外部存储现值的副本.
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
     * <p>数组按逻辑槽位新建, 元素沿用存储的读取契约.
     */
    @Override
    public @Nullable ItemStack @NotNull [] unsafeSnapshot() {
        return this.readView();
    }

    @Override
    public void refresh() {
        if (this.retired) return;
        if (!this.storage.alive()) {
            this.retire();
            return;
        }
        this.reconcileFromStorage();
    }

    /**
     * 释放外部存储并使本 Inventory 退役. 后续读取为空, 请求写入会冲突, 权威修改抛出 IllegalStateException, 重复调用没有效果.
     */
    public void retire() {
        if (this.retired) {
            return;
        }
        this.retired = true;
        // modCount 一动, 所有还在路上的规划基准当场失效, 它们提交时会自己发现.
        this.modCount++;
        this.storage = RETIRED_STORAGE;
        this.referenced = null;
        // 退役之后这些槽位换成一组只跟自己相等的身份, 不再和任何真实存储位置判等.
        for (int slot = 0; slot < this.slotKeys.length; slot++) {
            this.slotKeys[slot] = new SlotKey(this, slot);
        }
        Arrays.fill(this.lastKnown, null);
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
     * <p>返回外部存储声明的堆叠上限.
     */
    @Override
    public int slotMaxStackSize(int slot) {
        return this.storage.maxStackSize(this.storageSlots[slot]);
    }

    /**
     * {@inheritDoc}
     *
     * <p>玩家存储区按原版 quick-move 的反向顺序执行 ADD.
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
     * <p>返回构造时从外部存储取得的物理身份.
     */
    @Override
    @NotNull
    public SlotKey physicalKey(int slot) {
        return this.slotKeys[slot];
    }

    // 开始规划写入之前先同步一次. 外面可能已经改过内容, 也可能整个容器已经没了.
    @Override
    @ApiStatus.Internal
    public void prepareWrite() {
        this.refresh();
    }

    // 这里不交锁. 外部存储靠调用方在所属线程里串行访问来保证安全, 拿一把自己的锁只会给出虚假的安全感.
    @Override
    @Nullable
    @ApiStatus.Internal
    public PlannedRoot.StateLock stateLock() {
        return null;
    }

    // 规划基准是一份存储快照加上当时的 modCount. 提交时 modCount 变了就说明中间有人写过.
    @Override
    @NotNull
    @ApiStatus.Internal
    public PlannedRoot openPlan() {
        return new Live(this, this.readView(), this.modCount);
    }

    // 把变更写进外部存储, 同时更新 lastKnown, 免得下一次刷新把自己刚写的东西当成外部改动又报一遍.
    private void liveApply(@NotNull List<SlotChange> deltas) {
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            int storageSlot = this.storageSlots[delta.slot()];
            // 内容一样就不写了. 外部容器里那个实例可能正被别的代码拿着, 换掉它没好处.
            if (!this.storage.contentEquals(storageSlot, delta.unsafeAfter())) {
                this.storage.write(storageSlot, delta.after());
            }
            this.lastKnown[delta.slot()] = delta.after();
        }
        this.modCount++;
    }

    // 比对外部存储和 lastKnown, 把差异当成一笔 External 事务派发出去.
    // 这些改动在外面已经生效了, 所以只通知不回写, 也不走 Pre, 没有什么可以取消的.
    private void reconcileFromStorage() {
        if (this.retired) return;
        // 绝大多数刷新都没有差异, 这条路上一个变更列表和一个 Bukkit 物品包装都不分配.
        @Nullable List<SlotChange> deltas = null;
        for (int slot = 0; slot < this.lastKnown.length; slot++) {
            int storageSlot = this.storageSlots[slot];
            @Nullable ItemStack knownItem = this.lastKnown[slot];
            if (this.storage.contentEquals(storageSlot, knownItem)) {
                continue;
            }
            if (deltas == null) {
                deltas = new ArrayList<>();
            }
            deltas.add(new SlotChange(slot, knownItem, ItemUtils.nullIfEmpty(this.storage.read(storageSlot))));
        }
        if (deltas == null) {
            return;
        }

        // 先把新基准发布出去再派发事件. Post 处理器里可能又发起写入, 它得看到最新版本才不会白跑一趟冲突.
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            this.lastKnown[delta.slot()] = delta.unsafeAfter();
        }
        this.modCount++;
        TransactionResult result = InventoryTransactions.commitExternalSync(
                new TransactionScope(new Live(this, this.readView(), this.modCount), deltas)
        );
        // 只要所属线程串行访问这条契约还成立, External 同步就不可能撞上并发, 这里的结果一定是成功.
        if (!(result instanceof TransactionResult.Committed)) {
            SparrowUI.getInstance().handleException(
                    "Failed to dispatch external changes of a ReferencingInventory",
                    new IllegalStateException("external dispatch was rejected: " + result)
            );
        }
    }

    // 退役之后一律不碰存储, 直接给一份固定尺寸的空视图.
    private @Nullable ItemStack @NotNull [] readView() {
        if (this.retired) {
            return new ItemStack[this.storageSlots.length];
        }
        return this.mapView(this.storage.readAll());
    }

    // 按逻辑槽位的顺序重排存储内容. 只搬引用不复制物品, 读路径上这一层要尽量便宜.
    @Nullable
    private ItemStack @NotNull [] mapView(@Nullable ItemStack[] raw) {
        @Nullable ItemStack[] view = new ItemStack[this.storageSlots.length];
        for (int slot = 0; slot < view.length; slot++) {
            view[slot] = ItemUtils.nullIfEmpty(raw[this.storageSlots[slot]]);
        }
        return view;
    }

    private static @Nullable ItemStack[] readLogicalContents(@Nullable ItemStack[] raw, SlotOrder slotMapping) {
        @Nullable ItemStack[] logical = new ItemStack[raw.length];
        for (int slot = 0; slot < raw.length; slot++) {
            logical[slot] = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(raw[slotMapping.slotAt(slot)]));
        }
        return logical;
    }

    // 先给一份 0 到 size-1 的原样顺序, 让重排函数在它上面改.
    private static int[] identitySlots(int size) {
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return slots;
    }

    // 构造时把槽位映射一次摊平成数组. 这张表回答"我的第 n 格对应存储自己坐标里的哪一格",
    // 之后每次读写和比对都查它, 不再重复算映射.
    private static int[] storageSlots(SlotOrder slotMapping) {
        int[] storageSlots = new int[slotMapping.size()];
        for (int slot = 0; slot < storageSlots.length; slot++) {
            storageSlots[slot] = slotMapping.slotAt(slot);
        }
        return storageSlots;
    }

    // 物理身份也在构造时固化. 它和 storageSlots 分开存, 一个用来判同一格, 一个用来真读写, 混用会出事.
    private static SlotKey[] slotKeys(ExternalStorage storage, SlotOrder slotMapping) {
        SlotKey[] slotKeys = new SlotKey[slotMapping.size()];
        for (int slot = 0; slot < slotKeys.length; slot++) {
            slotKeys[slot] = storage.keyOf(slotMapping.slotAt(slot));
        }
        return slotKeys;
    }

    // 玩家背包的槽位顺序跟原版协议不一样, 这里把主背包放前面, 快捷栏挪到最后九格.
    private static int[] reorderPlayerStorage(int[] slots) {
        int[] reordered = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            reordered[i] = (slots[i] + 9) % 36;
        }
        return reordered;
    }

    // 外部存储的规划基准. 它没有内部状态数组可以比对, 失效判断全靠 modCount 有没有动过.
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
