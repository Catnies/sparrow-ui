package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * // TODO 是否有读同步也及时的方案?
 * 引用真实 Bukkit 库存的库存实现: Bukkit 容器是外部真相, 本类维护其镜像快照.
 * <p>读路径走镜像, 任意线程安全, 但可能滞后于外部变更, 直到下一次对账;
 * 写路径要求主线程, 经根级钩子接入事务管线: {@code beforePlan} 在任何写入口
 * (含视图的批量归约)读取规划快照前完成线程校验与对账, {@code afterCommit} 在
 * 提交成功后, post 事件派发前, 把变更写回容器 —— 全部 Bukkit 交互都在主线程
 * 串行, 不存在镜像与容器互相覆盖的窗口, post 观察者重入写时容器已同步.
 * 外部世界(漏斗, 其他插件)的直接修改由对账发现, 以 {@link UpdateReason.External} 只派发 post 事件.
 * <p>集成层(如 Window 渲染循环)应在主线程每 tick 调用 {@link #refresh()};
 * 本类自身不注册调度任务.
 * <p><b>Folia 不受支持</b>: 目标容器只能在其区域线程访问, 而区域随实体移动变化,
 * 任何跨线程引用视图都无法安全实现; 构造时立即失败.
 */
public final class ReferencingInventory extends AbstractInventory {
    private final org.bukkit.inventory.Inventory bukkitInventory;
    private final Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter; // 读取被引用的内容区段
    private final int[] toBukkitSlots; // 逻辑槽 -> Bukkit 槽的固定映射, 读取与写回共用
    private final int bukkitMaxStackSize; // 被引用容器的堆叠上限, 构造时缓存

    private ReferencingInventory(
            org.bukkit.inventory.Inventory bukkitInventory,
            Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter,
            UnaryOperator<int[]> slotReorder
    ) {
        super(initialMirror(bukkitInventory, contentsGetter, slotReorder));
        this.bukkitInventory = bukkitInventory;
        this.contentsGetter = contentsGetter;
        this.toBukkitSlots = slotReorder.apply(identitySlots(this.size()));
        this.bukkitMaxStackSize = bukkitInventory.getMaxStackSize();
    }

    /**
     * 引用给定容器的全部内容({@code getContents}).
     *
     * @throws UnsupportedOperationException 当运行在 Folia 上时
     * @throws IllegalStateException 当调用线程不是主线程时
     */
    @NotNull
    public static ReferencingInventory fromContents(@NotNull org.bukkit.inventory.Inventory inventory) {
        return create(inventory, org.bukkit.inventory.Inventory::getContents, UnaryOperator.identity(), VersionHelper.isFolia());
    }

    /**
     * 引用给定容器的存储内容({@code getStorageContents}, 不含盔甲与副手).
     *
     * @throws UnsupportedOperationException 当运行在 Folia 上时
     * @throws IllegalStateException 当调用线程不是主线程时
     */
    @NotNull
    public static ReferencingInventory fromStorageContents(@NotNull org.bukkit.inventory.Inventory inventory) {
        return create(inventory, org.bukkit.inventory.Inventory::getStorageContents, UnaryOperator.identity(), VersionHelper.isFolia());
    }

    /**
     * 引用玩家背包的存储内容, 并把热键行重排到最后九个逻辑槽:
     * 逻辑槽 {@code i} 对应真实槽 {@code (i + 9) % 36}, 使主背包在前, 热键行在后.
     *
     * @throws UnsupportedOperationException 当运行在 Folia 上时
     * @throws IllegalStateException 当调用线程不是主线程时
     */
    @NotNull
    public static ReferencingInventory fromPlayerStorageContents(@NotNull PlayerInventory inventory) {
        return create(inventory, org.bukkit.inventory.Inventory::getStorageContents, ReferencingInventory::reorderPlayerStorage, VersionHelper.isFolia());
    }

    // 工厂枢纽: Folia 检测值由公开工厂求值传入, 测试环境(VersionHelper 不可初始化)可直接调用本方法
    static ReferencingInventory create(
            org.bukkit.inventory.Inventory inventory,
            Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter,
            UnaryOperator<int[]> slotReorder,
            boolean folia
    ) {
        requireNotFolia(folia);
        return new ReferencingInventory(inventory, contentsGetter, slotReorder);
    }

    // 构造前置: 主线程校验后读取容器建立首个镜像
    private static @Nullable ItemStack[] initialMirror(
            org.bukkit.inventory.Inventory bukkitInventory,
            Function<org.bukkit.inventory.Inventory, @Nullable ItemStack[]> contentsGetter,
            UnaryOperator<int[]> slotReorder
    ) {
        requireMainThread();
        @Nullable ItemStack[] raw = contentsGetter.apply(bukkitInventory);
        return readLogicalContents(raw, slotReorder.apply(identitySlots(raw.length)));
    }

    static void requireNotFolia(boolean folia) {
        if (folia) {
            throw new UnsupportedOperationException(
                    "ReferencingInventory is not supported on Folia: the referenced container is only accessible from its region thread, which changes as entities move"
            );
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ReferencingInventory requires the main thread for construction and writes; reads work from any thread");
        }
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
     * 只能在主线程调用; 集成层应每 tick 调用一次.
     *
     * @throws IllegalStateException 当调用线程不是主线程时
     */
    @Override
    public void refresh() {
        requireMainThread();
        this.reconcileFromBukkit();
    }

    @Override
    public int slotMaxStackSize(int slot) {
        Objects.checkIndex(slot, this.size());
        return this.bukkitMaxStackSize;
    }

    // 写前钩子: 任何写入口(本库存方法或视图批量归约)都先经这里 —— 主线程校验
    // 加对账, 使规划基于容器的最新真相
    @Override
    void beforePlan() {
        requireMainThread();
        this.reconcileFromBukkit();
    }

    // 提交后钩子: 引擎在 post 派发前调用, 把镜像变更写回容器. delta 访问器返回
    // 克隆, 容器不会持有镜像内部实例
    @Override
    void afterCommit(@NotNull List<SlotDelta> deltas) {
        for (int i = 0; i < deltas.size(); i++) {
            SlotDelta delta = deltas.get(i);
            this.bukkitInventory.setItem(this.toBukkitSlots[delta.slot()], delta.after());
        }
    }

    // 对账: diff 容器当前内容与镜像, 差异以 External 原因绕过 pre 提交进镜像.
    // 比较阶段直接用容器的镜像包装引用, 不做任何深克隆 —— 绝大多数 tick 没有外部
    // 变更, 只有差异槽才在 SlotDelta 构造中克隆归一. 主线程串行保证镜像无并发
    // 写者, 该提交不应失败; 防御性上报意外结果.
    private void reconcileFromBukkit() {
        @Nullable ItemStack[] raw = this.contentsGetter.apply(this.bukkitInventory);
        @Nullable ItemStack[] mirror = this.currentState();
        @Nullable List<SlotDelta> deltas = null;
        for (int slot = 0; slot < mirror.length; slot++) {
            @Nullable ItemStack liveItem = raw[this.toBukkitSlots[slot]];
            @Nullable ItemStack mirrorItem = mirror[slot];
            boolean equal = ItemUtils.isEmpty(liveItem) ? mirrorItem == null : liveItem.equals(mirrorItem);
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
        if (!(result instanceof TransactionResult.Committed)) {
            SparrowUI.getInstance().handleException(
                    "Failed to reconcile ReferencingInventory mirror",
                    new IllegalStateException("reconcile commit was rejected: " + result)
            );
        }
    }

    // 按逻辑槽序读取容器内容, 元素克隆并归一化为镜像约定
    private static @Nullable ItemStack[] readLogicalContents(@Nullable ItemStack[] raw, int[] toBukkitSlots) {
        @Nullable ItemStack[] logical = new ItemStack[raw.length];
        for (int slot = 0; slot < raw.length; slot++) {
            logical[slot] = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(raw[toBukkitSlots[slot]]));
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

    // 玩家背包重排: 逻辑槽 i -> 真实槽 (i + 9) % 36, 热键行(真实 0-8)落到逻辑 27-35
    private static int[] reorderPlayerStorage(int[] slots) {
        int[] reordered = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            reordered[i] = (slots[i] + 9) % 36;
        }
        return reordered;
    }
}
