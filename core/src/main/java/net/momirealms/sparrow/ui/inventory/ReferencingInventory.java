package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
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
 * 内容放在外部存储里的 Inventory 实现.
 * <p><strong>串行访问由调用方负责.</strong> 所有读写都必须在存储可合法访问的上下文中串行执行
 * (Bukkit 存储即容器的属主线程); 接入 Window 期间, 玩家实体线程就是事实上的串行化线程.
 * 本类不判断平台或存储的执行所有者, 也不调度到其线程. 平台抛出的访问异常会沿调用栈传播;
 * <p>规划基准是新建时逐槽读存储填出来的临时数组, 每次规划都重新建一份, 只用来读内容;
 * {@code prepareWrite} 在任何写入口读取规划内容之前先比对一次, 把积压的外部变更先派发出去.
 * <p>Window 每个 tick 调用一次 {@link #refresh()}; 本类自己不注册调度任务.
 */
@ApiStatus.Experimental
public final class ReferencingInventory extends SparrowInventory {
    private final ExternalStorage storage;   // 内容实际存放的地方, 读写一律以它为准
    private final SlotKey[] externalSlots;   // 当前 Inventory 槽位 -> 存储槽位, 读写与比对共用
    private final @Nullable SlotOrder addOrder;     // 玩家存储区的 ADD 顺序按原版 quick-move 反向遍历, 其余情况为 null

    private final @Nullable ItemStack[] lastKnown;  // 上次见到的内容, 逐槽一份副本, 只用来发现外部改动
    private volatile boolean retired;               // 存储已经不在了, 之后读到空, 写入一律失败
    private long modCount;                          // 并发校验用的计数, 自己写入或吸收外部变更后加一

    /**
     * 以给定外部存储和一份初始内容创建 ReferencingInventory.
     * <p>交给基类的状态数组只是用来提供槽位数量: 本类的内容读写全部改道外部存储,
     * 它永远是空的, 也永远不会被交换.
     *
     * @param storage 内容实际存放的外部存储
     * @param initialKnown lastKnown 的初值, 已按当前 Inventory 槽位排列, 空物品已转为 {@code null}
     * @param slotMapping 当前 Inventory 槽位到存储槽位的映射
     * @param addOrder ADD 类别的遍历顺序, {@code null} 回退自然顺序
     */
    private ReferencingInventory(
            ExternalStorage storage,
            @Nullable ItemStack[] initialKnown,
            SlotOrder slotMapping,
            @Nullable SlotOrder addOrder
    ) {
        super(new ItemStack[initialKnown.length]);
        this.storage = storage;
        this.externalSlots = externalSlots(storage.identity(), slotMapping);
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
        return new ReferencingInventory(storage, readLogicalContents(raw, slotMapping), slotMapping, null);
    }

    /**
     * 创建 ReferencingInventory.
     *
     * @param inventory 被引用的 Bukkit 容器
     * @param contentsGetter 从容器读取被引用区段的函数
     * @param slotReorder 当前 Inventory 槽位到 Bukkit 容器槽位的重排函数
     * @param reverseAddOrder 是否给 ADD 类别使用反向遍历顺序
     * @return ReferencingInventory
     * @throws IllegalArgumentException 当重排后的映射尺寸与内容尺寸不符时
     */
    static ReferencingInventory create(
            Inventory inventory,
            Function<Inventory, @Nullable ItemStack[]> contentsGetter,
            UnaryOperator<int[]> slotReorder,
            boolean reverseAddOrder
    ) {
        ExternalStorage storage = BukkitStorage.of(inventory, contentsGetter);
        @Nullable ItemStack[] raw = storage.readAll();
        SlotOrder slotMapping = SlotOrder.of(slotReorder.apply(identitySlots(raw.length)));
        if (slotMapping.size() != raw.length) {
            throw new IllegalArgumentException("slot mapping size " + slotMapping.size() + " does not match contents size " + raw.length);
        }
        @Nullable SlotOrder addOrder = reverseAddOrder ? SlotOrder.natural(raw.length).reversed() : null;
        return new ReferencingInventory(
                storage,
                readLogicalContents(raw, slotMapping),
                slotMapping,
                addOrder
        );
    }

    /**
     * 返回被引用的 Bukkit 容器, 如果存储实现不是 Inventory 则返回 {@code null}.
     *
     * @return 被引用的容器
     */
    @Nullable
    public Inventory referencedInventory() {
        return this.storage.identity() instanceof Inventory inventory ? inventory : null;
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
     * {@link TransactionResult.Conflicted} 收场), 快速转移与双击收集也不再把它当成目标. 展示它的
     * Window 会把那些槽位重新渲染成空.
     * <p>可以直接调用(例如在方块破坏事件里), 也可以交给 {@link ExternalStorage#alive()} 让每 tick 的
     * {@link #refresh()} 自己发现. 重复调用没有额外效果.
     */
    public void retire() {
        if (this.retired) {
            return;
        }
        this.retired = true;
        // 作废全部在途规划基准, 之后新建的基准由 PlannedRoot.Live.isStale 一律判定失效.
        this.modCount++;
        this.publishVisualDirty(ALL_SLOTS);
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
     * <p>返回存储槽对应的 SlotKey: 两个 ReferencingInventory 指向同一存储归属和同一存储槽位时, SlotKey 相同.
     */
    @Override
    @NotNull
    SlotKey physicalKey(int slot) {
        return this.externalSlots[slot];
    }

    /**
     * {@inheritDoc}
     *
     * <p>先比对一次, 把积压的外部变更派发出去, 再基于最新内容规划.
     */
    @Override
    void prepareWrite() {
        this.reconcileFromStorage();
    }

    /**
     * {@inheritDoc}
     *
     * <p>新建一份规划基准: 逐槽读存储填出一个临时数组, 再记下当前的 modCount.
     */
    @Override
    @NotNull
    PlannedRoot openPlan() {
        return new PlannedRoot.Live(this, this.readView(), this.modCount);
    }

    // 当前 modCount, 供本 Inventory 建出的规划基准判断自己有没有失效.
    long liveModCount() {
        return this.modCount;
    }

    /**
     * 把本写集的槽位变更落进外部存储: 存储现值已经和要提交的内容相等就跳过, 免得一次等值覆盖
     * 白白作废外部拿着的引用; 其余槽位换成新实例写进去.
     * 写完同步 lastKnown(引擎自己写进去的东西不能在下一轮比对里被当成外部改动), 并把 modCount 加一.
     *
     * @param deltas 本写集的槽位变更
     */
    void liveApply(@NotNull List<SlotChange> deltas) {
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            int externalSlot = this.externalSlots[delta.slot()].slot();
            if (!ItemUtils.isContentEqual(this.storage.read(externalSlot), delta.unsafeAfter())) {
                this.storage.write(externalSlot, delta.after());
            }
            this.lastKnown[delta.slot()] = delta.after();
        }
        this.modCount++;
    }

    /**
     * 把存储现在的内容和 lastKnown 逐槽比一遍, 有差异就收下来, 并以 External 原因派发 post 事件.
     * 收下来 = lastKnown 改记现值 + modCount 加一(在途规划全部作废); 不回写存储, 存储里的物品实例原样不动.
     * 调用方保证运行期访问被正确串行化, 因此派发被拒绝说明调用边界被破坏, 交给统一异常处理器上报.
     */
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
                new TransactionScope(new PlannedRoot.Live(this, this.mapView(raw), this.modCount), deltas)
        );
        // 冲突在调用方保证的串行访问下不该发生, 视为调用边界被破坏并上报
        if (!(result instanceof TransactionResult.Committed)) {
            SparrowUI.getInstance().handleException(
                    "Failed to dispatch external changes of a ReferencingInventory",
                    new IllegalStateException("external dispatch was rejected: " + result)
            );
        }
    }

    /**
     * 读出存储当前全部内容, 按当前 Inventory 槽位排列.
     * <p>退役之后不再去碰存储: 那个容器已经与服务端脱钩, 里面剩下的东西不该再出现在菜单里,
     * 也不该成为任何一笔事务的规划依据, 所以这里给出的是一整排空槽位.
     *
     * @return 按当前 Inventory 槽位排列的视图数组
     */
    private @Nullable ItemStack @NotNull [] readView() {
        if (this.retired) {
            return new ItemStack[this.externalSlots.length];
        }
        return this.mapView(this.storage.readAll());
    }

    /**
     * 把存储原始内容按当前 Inventory 槽位顺序整理成视图数组, 元素零拷贝, 空物品折为 {@code null}.
     *
     * @param raw 存储原始内容
     * @return 按当前 Inventory 槽位排列的视图数组
     */
    @Nullable
    private ItemStack @NotNull [] mapView(@Nullable ItemStack[] raw) {
        @Nullable ItemStack[] view = new ItemStack[this.externalSlots.length];
        for (int slot = 0; slot < view.length; slot++) {
            view[slot] = ItemUtils.nullIfEmpty(raw[this.externalSlots[slot].slot()]);
        }
        return view;
    }

    /**
     * 按当前 Inventory 槽位顺序从存储原始内容取样, 复制成 lastKnown 的初值(空槽为 {@code null}).
     *
     * @param raw 存储原始内容
     * @param slotMapping 当前 Inventory 槽位到存储槽位的映射
     * @return 按当前 Inventory 槽位排列的内容副本
     */
    private static @Nullable ItemStack[] readLogicalContents(@Nullable ItemStack[] raw, SlotOrder slotMapping) {
        @Nullable ItemStack[] logical = new ItemStack[raw.length];
        for (int slot = 0; slot < raw.length; slot++) {
            logical[slot] = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(raw[slotMapping.slotAt(slot)]));
        }
        return logical;
    }

    /**
     * 生成 0 到 size-1 的恒等槽位数组, 供重排函数加工.
     *
     * @param size 槽位数量
     * @return 恒等槽位数组
     */
    private static int[] identitySlots(int size) {
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return slots;
    }

    /**
     * 为每个当前 Inventory 槽位建立对应的 {@link SlotKey}.
     *
     * @param identity 存储归属
     * @param slotMapping 当前 Inventory 槽位到存储槽位的映射
     * @return 每个当前 Inventory 槽位的 SlotKey
     */
    private static SlotKey[] externalSlots(Object identity, SlotOrder slotMapping) {
        SlotKey[] externalSlots = new SlotKey[slotMapping.size()];
        for (int slot = 0; slot < slotMapping.size(); slot++) {
            externalSlots[slot] = new SlotKey(identity, slotMapping.slotAt(slot));
        }
        return externalSlots;
    }

    /**
     * 玩家背包重排: 当前 Inventory 槽位 {@code i} 指向 Bukkit 容器槽位 {@code (i + 9) % 36},
     * 热键行(Bukkit 容器槽位 0-8)因此落到当前 Inventory 槽位 27-35.
     *
     * @param slots 恒等槽位数组
     * @return 重排后的槽位数组
     */
    private static int[] reorderPlayerStorage(int[] slots) {
        int[] reordered = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            reordered[i] = (slots[i] + 9) % 36;
        }
        return reordered;
    }

}
