package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 引用真实 Bukkit 容器的 Inventory 实现: 容器是真实数据的所在地, 本类只维护它的一份镜像快照.
 * <p>构造时只读取一次初始内容, 不限制调用线程. 读操作走镜像, 任何线程都安全, 但内容可能
 * 滞后于容器, 要等下一次同步才更新. 普通 Paper 上不做线程判断; Folia 上按容器实际 owner
 * 动态判断: 当前线程访问不了时 refresh 安静跳过, 写事务返回 {@link TransactionResult.Unavailable},
 * 回到 owner 线程后同一个实例恢复读写.
 * <p>写路径靠两个根级钩子接入事务流程: {@code beforePlan} 在任何写入口读取规划快照之前
 * 同步容器内容, {@code afterCommit} 在提交成功后, post 事件派发前把变更写回容器.
 * 外部世界(漏斗, 其他插件)对容器的直接修改在同步时被发现, 以 {@link UpdateReason.External}
 * 原因只派发 post 事件.
 * <p>Window 每个 tick 调用一次 {@link #refresh()}; 本类自己不注册
 * 调度任务, 也不会主动切到 owner 线程, 阻塞等待或拆分跨 owner 的事务.
 */
public final class ReferencingInventory extends AbstractInventory {
    private final org.bukkit.inventory.Inventory bukkitInventory; // 被引用的 Bukkit 容器, 真实数据所在地
    private final Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter; // 从容器读取被引用区段(getContents / getStorageContents)
    private final SlotKey.ExternalSlot[] externalSlots; // 逻辑槽 -> 容器里的真实槽位, 同步与写回共用
    private final int bukkitMaxStackSize;           // 容器的堆叠上限, 构造时缓存
    private final BooleanSupplier writeAvailable;   // 当前线程能否访问容器的动态判断
    private final @Nullable SlotOrder addOrder;     // 玩家存储区的 ADD 顺序按原版 quick-move 反向遍历, 其余情况为 null

    /**
     * 以给定容器与初始镜像创建 ReferencingInventory.
     *
     * @param bukkitInventory 被引用的 Bukkit 容器
     * @param contentsGetter 从容器读取被引用区段的函数
     * @param initialMirror 初始镜像内容, 已按逻辑槽序排列并归一化
     * @param slotMapping 逻辑槽到容器槽位的映射
     * @param writeAvailable 当前线程能否访问容器的动态判断
     * @param addOrder ADD 类别的遍历顺序, {@code null} 回退自然顺序
     */
    private ReferencingInventory(
            org.bukkit.inventory.Inventory bukkitInventory,
            Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter,
            @Nullable ItemStack[] initialMirror,
            SlotOrder slotMapping,
            BooleanSupplier writeAvailable,
            @Nullable SlotOrder addOrder
    ) {
        super(initialMirror);
        this.bukkitInventory = bukkitInventory;
        this.contentsGetter = contentsGetter;
        this.externalSlots = externalSlots(bukkitInventory, slotMapping);
        this.bukkitMaxStackSize = bukkitInventory.getMaxStackSize();
        this.writeAvailable = writeAvailable;
        this.addOrder = addOrder;
    }

    /**
     * 引用容器的全部内容({@code getContents}).
     *
     * @param inventory 被引用的 Bukkit 容器
     * @return ReferencingInventory
     */
    @NotNull
    public static ReferencingInventory fromContents(@NotNull org.bukkit.inventory.Inventory inventory) {
        return create(inventory, org.bukkit.inventory.Inventory::getContents, UnaryOperator.identity(), false);
    }

    /**
     * 引用容器的存储内容({@code getStorageContents}, 不含盔甲与副手).
     *
     * @param inventory 被引用的 Bukkit 容器
     * @return ReferencingInventory
     */
    @NotNull
    public static ReferencingInventory fromStorageContents(@NotNull org.bukkit.inventory.Inventory inventory) {
        return create(inventory, org.bukkit.inventory.Inventory::getStorageContents, UnaryOperator.identity(), false);
    }

    /**
     * 引用玩家背包的存储内容, 并把热键行挪到最后九个逻辑槽:
     * 逻辑槽 {@code i} 对应真实槽 {@code (i + 9) % 36}, 主背包在前, 快捷栏在后.
     * ADD 操作按原版 quick-move 的习惯从热键行尾部反向遍历.
     *
     * @param inventory 玩家背包
     * @return ReferencingInventory
     */
    @NotNull
    public static ReferencingInventory fromPlayerStorageContents(@NotNull PlayerInventory inventory) {
        return create(inventory, org.bukkit.inventory.Inventory::getStorageContents, ReferencingInventory::reorderPlayerStorage, true);
    }

    /**
     * 创建 ReferencingInventory, 线程判断按当前服务端是否 Folia 自动推导.
     *
     * @param inventory 被引用的 Bukkit 容器
     * @param contentsGetter 从容器读取被引用区段的函数
     * @param slotReorder 逻辑槽到真实槽的重排函数, 恒等表示不重排
     * @param reverseAddOrder 是否给 ADD 类别使用反向遍历顺序
     * @return ReferencingInventory
     */
    private static ReferencingInventory create(
            org.bukkit.inventory.Inventory inventory,
            Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter,
            UnaryOperator<int[]> slotReorder,
            boolean reverseAddOrder
    ) {
        return create(
                inventory,
                contentsGetter,
                slotReorder,
                () -> currentThreadCanWrite(inventory, VersionHelper.isFolia()),
                reverseAddOrder
        );
    }

    /**
     * 创建 ReferencingInventory.
     *
     * @param inventory 被引用的 Bukkit 容器
     * @param contentsGetter 从容器读取被引用区段的函数
     * @param slotReorder 逻辑槽到真实槽的重排函数
     * @param writeAvailable 当前线程能否访问容器的动态判断
     * @param reverseAddOrder 是否给 ADD 类别使用反向遍历顺序
     * @return ReferencingInventory
     * @throws IllegalArgumentException 当重排后的映射尺寸与内容尺寸不符时
     */
    static ReferencingInventory create(
            org.bukkit.inventory.Inventory inventory,
            Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter,
            UnaryOperator<int[]> slotReorder,
            BooleanSupplier writeAvailable,
            boolean reverseAddOrder
    ) {
        @Nullable ItemStack[] raw = contentsGetter.apply(inventory);
        SlotOrder slotMapping = SlotOrder.of(slotReorder.apply(identitySlots(raw.length)));
        if (slotMapping.size() != raw.length) {
            throw new IllegalArgumentException("slot mapping size " + slotMapping.size() + " does not match contents size " + raw.length);
        }
        @Nullable SlotOrder addOrder = reverseAddOrder ? reverseOrder(raw.length) : null;
        return new ReferencingInventory(
                inventory,
                contentsGetter,
                readLogicalContents(raw, slotMapping),
                slotMapping,
                writeAvailable,
                addOrder
        );
    }

    /**
     * 返回被引用的 Bukkit 容器.
     *
     * @return 被引用的容器
     */
    @NotNull
    public org.bukkit.inventory.Inventory referencedInventory() {
        return this.bukkitInventory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void refresh() {
        if (!this.writeAvailable()) {
            return;
        }
        this.reconcileFromBukkit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>返回容器自身的堆叠上限.
     */
    @Override
    public int slotMaxStackSize(int slot) {
        Objects.checkIndex(slot, this.size());
        return this.bukkitMaxStackSize;
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
     * <p>落点是容器里的真实槽位: 两个镜像指向同一个 Bukkit 槽时物理身份相同.
     */
    @Override
    @NotNull
    SlotKey rootPhysicalKey(@NotNull SlotKey.Anchor anchor) {
        return this.externalSlots[anchor.rootSlot()];
    }

    /**
     * {@inheritDoc}
     *
     * <p>委托给构造时注入的动态判断;
     * 判断过程抛出 {@link IllegalStateException} 时按不可访问处理.
     */
    @Override
    boolean writeAvailable() {
        try {
            return this.writeAvailable.getAsBoolean();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>先把容器最新内容同步进镜像, 规划才基于最新数据.
     */
    @Override
    void beforePlan() {
        this.reconcileFromBukkit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>把每个槽位变更写回容器对应的真实槽位.
     */
    @Override
    void afterCommit(@NotNull List<SlotDelta> deltas) {
        // delta 的访问器返回克隆, 容器不会拿到镜像内部实例
        for (int i = 0; i < deltas.size(); i++) {
            SlotDelta delta = deltas.get(i);
            this.bukkitInventory.setItem(this.externalSlots[delta.slot()].slot(), delta.after());
        }
    }

    /**
     * 把容器当前内容和镜像逐槽对比, 差异槽以 External 原因提交进镜像(绕过 pre, 只派发 post).
     * owner 线程会串行化运行期访问, 因此提交被拒绝说明调用边界被破坏, 交给统一异常处理器上报.
     */
    private void reconcileFromBukkit() {
        // 逐槽对比: 比较阶段直接拿容器读出的引用, 不做深克隆 —— 绝大多数 tick 没有外部变更,
        // 只有差异槽才在 SlotDelta 构造里克隆
        @Nullable ItemStack[] raw = this.contentsGetter.apply(this.bukkitInventory);
        @Nullable ItemStack[] mirror = this.currentState();
        @Nullable List<SlotDelta> deltas = null;
        for (int slot = 0; slot < mirror.length; slot++) {
            @Nullable ItemStack liveItem = raw[this.externalSlots[slot].slot()];
            @Nullable ItemStack mirrorItem = mirror[slot];
            boolean equal = ItemUtils.isNullOrEmpty(liveItem) ? mirrorItem == null : liveItem.equals(mirrorItem);
            if (!equal) {
                if (deltas == null) {
                    deltas = new ArrayList<>();
                }
                deltas.add(new SlotDelta(slot, mirrorItem, liveItem));
            }
        }
        if (deltas == null) {
            return;
        }

        // 提交前可访问性变了就放弃这次同步, 等下一次
        TransactionResult result = InventoryTransactions.commit(
                UpdateReason.External.INSTANCE,
                List.of(new InventoryTransactions.Scope(this, mirror, deltas)),
                true
        );
        if (result == TransactionResult.Unavailable.INSTANCE) {
            return;
        }
        // 冲突在 owner 线程串行访问下不该发生, 视为调用边界被破坏并上报
        if (!(result instanceof TransactionResult.Committed)) {
            SparrowUI.getInstance().handleException(
                    "Failed to reconcile ReferencingInventory mirror",
                    new IllegalStateException("reconcile commit was rejected: " + result)
            );
        }
    }

    /**
     * 按逻辑槽顺序从容器原始内容取样, 克隆成镜像约定(空槽为 {@code null}).
     *
     * @param raw 容器原始内容
     * @param slotMapping 逻辑槽到容器槽位的映射
     * @return 按逻辑槽排列的镜像内容
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
     * 生成从尾到头的反向遍历顺序.
     *
     * @param size 槽位数量
     * @return 反向遍历顺序
     */
    private static SlotOrder reverseOrder(int size) {
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = size - i - 1;
        }
        return SlotOrder.of(slots);
    }

    /**
     * 为每个逻辑槽建立指向容器真实槽位的物理身份.
     *
     * @param inventory 被引用的容器
     * @param slotMapping 逻辑槽到容器槽位的映射
     * @return 每个逻辑槽的最终物理身份
     */
    private static SlotKey.ExternalSlot[] externalSlots(org.bukkit.inventory.Inventory inventory, SlotOrder slotMapping) {
        SlotKey.ExternalSlot[] externalSlots = new SlotKey.ExternalSlot[slotMapping.size()];
        for (int slot = 0; slot < slotMapping.size(); slot++) {
            externalSlots[slot] = new SlotKey.ExternalSlot(inventory, slotMapping.slotAt(slot));
        }
        return externalSlots;
    }

    /**
     * 判断当前线程能否写入容器:
     * 非 Folia 恒可写, Folia 要求当前线程拥有容器所在的区域.
     *
     * @param inventory 被引用的容器
     * @param folia 当前服务端是否 Folia
     * @return 可写返回 {@code true}
     */
    static boolean currentThreadCanWrite(org.bukkit.inventory.Inventory inventory, boolean folia) {
        if (!folia) return true;
        return currentThreadOwns(inventory);
    }

    /**
     * 推导容器的归属并判断当前线程是否拥有它: 大箱子要两侧都要拥有, 实体与方块按区域归属判断,
     * 都没有归属时退化为按容器位置判断, 位置也拿不到就只认主线程.
     *
     * @param inventory 被引用的容器
     * @return 当前线程拥有容器返回 {@code true}
     */
    private static boolean currentThreadOwns(org.bukkit.inventory.Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false); // todo 这个方法好像本身就会访问目标线程, 然后爆炸
        if (holder instanceof DoubleChest doubleChest) {
            return currentThreadOwnsHolder(doubleChest.getLeftSide(false))
                    && currentThreadOwnsHolder(doubleChest.getRightSide(false));
        }
        if (holder instanceof Entity entity) {
            return Bukkit.isOwnedByCurrentRegion(entity);
        }
        if (holder instanceof BlockInventoryHolder blockHolder) {
            return Bukkit.isOwnedByCurrentRegion(blockHolder.getBlock());
        }
        Location location = inventory.getLocation();
        return location != null ? Bukkit.isOwnedByCurrentRegion(location) : Bukkit.isPrimaryThread();
    }

    /**
     * 判断当前线程是否拥有单个持有者(实体或方块).
     *
     * @param holder 容器持有者, 可为 {@code null}
     * @return 当前线程拥有该持有者返回 {@code true}
     */
    private static boolean currentThreadOwnsHolder(@Nullable InventoryHolder holder) {
        if (holder instanceof Entity entity) {
            return Bukkit.isOwnedByCurrentRegion(entity);
        }
        if (holder instanceof BlockInventoryHolder blockHolder) {
            return Bukkit.isOwnedByCurrentRegion(blockHolder.getBlock());
        }
        return false;
    }

    /**
     * 玩家背包重排: 逻辑槽 {@code i} 指向真实槽 {@code (i + 9) % 36},
     * 热键行(真实槽 0-8)因此落到逻辑槽 27-35.
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
