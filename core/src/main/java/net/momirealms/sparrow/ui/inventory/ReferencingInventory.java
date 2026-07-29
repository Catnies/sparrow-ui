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
 * 引用真实 Bukkit 库存的库存实现: Bukkit 容器是外部真相, 本类维护其镜像快照.
 * <p>构造只读取初始 contents, 不施加线程亲和限制. 读路径走镜像, 任意线程安全,
 * 但可能滞后于外部变更, 直到下一次成功对账. 普通 Paper 不增加线程判断; Folia
 * 的 refresh 与写路径按引用目标的实际 owner 动态判断: 当前线程不可访问时
 * refresh 静默跳过, 写事务返回 {@link TransactionResult.Unavailable}; 回到 owner
 * 线程后同一实例恢复读写.
 * 写路径经根级钩子接入事务管线: {@code beforePlan} 在任何写入口(含视图的批量归约)
 * 读取规划快照前对账, {@code afterCommit} 在提交成功后、post 事件派发前把变更写回容器.
 * 外部世界(漏斗, 其他插件)的直接修改由对账发现, 以 {@link UpdateReason.External} 只派发 post 事件.
 * <p>集成层(如 Window 渲染循环)应每 tick 调用 {@link #refresh()}; 本类自身不注册调度任务.
 * 本类不会调度到目标 owner、阻塞等待或拆分跨 owner 事务.
 */
public final class ReferencingInventory extends AbstractInventory {
    private final org.bukkit.inventory.Inventory bukkitInventory;
    private final Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter; // 读取被引用的内容区段
    private final SlotKey.ExternalSlot[] externalSlots; // 逻辑槽 -> 驻留的最终外部槽身份, 读取与写回共用
    private final int bukkitMaxStackSize; // 被引用容器的堆叠上限, 构造时缓存
    private final BooleanSupplier writeAvailable;
    private final @Nullable SlotOrder addOrder; // 玩家 storage 按原版 quick-move 反向遍历

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
     * 引用给定容器的全部内容({@code getContents}).
     */
    @NotNull
    public static ReferencingInventory fromContents(@NotNull org.bukkit.inventory.Inventory inventory) {
        return create(inventory, org.bukkit.inventory.Inventory::getContents, UnaryOperator.identity(), false);
    }

    /**
     * 引用给定容器的存储内容({@code getStorageContents}, 不含盔甲与副手).
     */
    @NotNull
    public static ReferencingInventory fromStorageContents(@NotNull org.bukkit.inventory.Inventory inventory) {
        return create(inventory, org.bukkit.inventory.Inventory::getStorageContents, UnaryOperator.identity(), false);
    }

    /**
     * 引用玩家背包的存储内容, 并把热键行重排到最后九个逻辑槽:
     * 逻辑槽 {@code i} 对应真实槽 {@code (i + 9) % 36}, 使主背包在前, 热键行在后.
     * ADD 操作按原版 quick-move 顺序从热键尾部反向遍历.
     */
    @NotNull
    public static ReferencingInventory fromPlayerStorageContents(@NotNull PlayerInventory inventory) {
        return create(inventory, org.bukkit.inventory.Inventory::getStorageContents, ReferencingInventory::reorderPlayerStorage, true);
    }

    private static ReferencingInventory create(
            org.bukkit.inventory.Inventory inventory,
            Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter,
            UnaryOperator<int[]> slotReorder,
            boolean reverseAddOrder
    ) {
        boolean folia = VersionHelper.isFolia();
        return create(
                inventory,
                contentsGetter,
                slotReorder,
                () -> currentThreadCanWrite(inventory, folia),
                reverseAddOrder
        );
    }

    // 包内测试 seam: 生产工厂统一使用 Bukkit owner 推导, 测试可控制动态 owner 变化
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
     * 被引用的 Bukkit 容器.
     */
    @NotNull
    public org.bukkit.inventory.Inventory referencedInventory() {
        return this.bukkitInventory;
    }

    /**
     * 从 Bukkit 容器对账: 吸收外部世界的直接修改进镜像, 以 External 原因只派发 post 事件.
     * 当前线程不拥有引用目标时静默跳过; 集成层可以在每 tick 无条件调用.
     */
    @Override
    public void refresh() {
        if (!this.writeAvailable()) {
            return;
        }
        this.reconcileFromBukkit();
    }

    @Override
    public int slotMaxStackSize(int slot) {
        Objects.checkIndex(slot, this.size());
        return this.bukkitMaxStackSize;
    }

    @Override
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        return category == OperationCategory.ADD && this.addOrder != null
                ? this.addOrder
                : super.iterationOrder(category);
    }

    @Override
    @NotNull
    SlotKey rootPhysicalKey(@NotNull SlotKey.Anchor anchor) {
        return this.externalSlots[anchor.rootSlot()];
    }

    @Override
    boolean writeAvailable() {
        try {
            return this.writeAvailable.getAsBoolean();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    // 写前钩子: 任何写入口(本库存方法或视图批量归约)都先对账, 使规划基于容器的最新真相
    @Override
    void beforePlan() {
        this.reconcileFromBukkit();
    }

    // 提交后钩子: 引擎在 post 派发前调用, 把镜像变更写回容器. delta 访问器返回
    // 克隆, 容器不会持有镜像内部实例
    @Override
    void afterCommit(@NotNull List<SlotDelta> deltas) {
        for (int i = 0; i < deltas.size(); i++) {
            SlotDelta delta = deltas.get(i);
            this.bukkitInventory.setItem(this.externalSlots[delta.slot()].slot(), delta.after());
        }
    }

    // 对账: diff 容器当前内容与镜像, 差异以 External 原因绕过 pre 提交进镜像.
    // 比较阶段直接用容器的镜像包装引用, 不做任何深克隆 —— 绝大多数 tick 没有外部
    // 变更, 只有差异槽才在 SlotDelta 构造中克隆归一. owner 线程串行化运行期访问,
    // 因此提交冲突表示调用边界被破坏, 交给统一异常处理器报告.
    private void reconcileFromBukkit() {
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

        TransactionResult result = InventoryTransactions.commit(
                UpdateReason.External.INSTANCE,
                List.of(new InventoryTransactions.Scope(this, mirror, deltas)),
                true
        );
        if (result == TransactionResult.Unavailable.INSTANCE) {
            return;
        }
        if (!(result instanceof TransactionResult.Committed)) {
            SparrowUI.getInstance().handleException(
                    "Failed to reconcile ReferencingInventory mirror",
                    new IllegalStateException("reconcile commit was rejected: " + result)
            );
        }
    }

    // 按逻辑槽序读取容器内容, 元素克隆并归一化为镜像约定
    private static @Nullable ItemStack[] readLogicalContents(@Nullable ItemStack[] raw, SlotOrder slotMapping) {
        @Nullable ItemStack[] logical = new ItemStack[raw.length];
        for (int slot = 0; slot < raw.length; slot++) {
            logical[slot] = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(raw[slotMapping.slotAt(slot)]));
        }
        return logical;
    }

    private static int[] identitySlots(int size) {
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return slots;
    }

    private static SlotOrder reverseOrder(int size) {
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = size - i - 1;
        }
        return SlotOrder.of(slots);
    }

    private static SlotKey.ExternalSlot[] externalSlots(org.bukkit.inventory.Inventory inventory, SlotOrder slotMapping) {
        SlotKey.ExternalSlot[] externalSlots = new SlotKey.ExternalSlot[slotMapping.size()];
        for (int slot = 0; slot < slotMapping.size(); slot++) {
            externalSlots[slot] = new SlotKey.ExternalSlot(inventory, slotMapping.slotAt(slot));
        }
        return externalSlots;
    }

    static boolean currentThreadCanWrite(org.bukkit.inventory.Inventory inventory, boolean folia) {
        if (!folia) {
            return true;
        }
        return currentThreadOwns(inventory);
    }

    private static boolean currentThreadOwns(org.bukkit.inventory.Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
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

    private static boolean currentThreadOwnsHolder(@Nullable InventoryHolder holder) {
        if (holder instanceof Entity entity) {
            return Bukkit.isOwnedByCurrentRegion(entity);
        }
        if (holder instanceof BlockInventoryHolder blockHolder) {
            return Bukkit.isOwnedByCurrentRegion(blockHolder.getBlock());
        }
        return false;
    }

    // 玩家背包重排: 逻辑槽 i -> 真实槽 (i + 9) % 36, 热键行(真实 0-8)落到逻辑 27-35
    private static int[] reorderPlayerStorage(int[] slots) {
        int[] reordered = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            reordered[i] = (slots[i] + 9) % 36;
        }
        return reordered;
    }

}
