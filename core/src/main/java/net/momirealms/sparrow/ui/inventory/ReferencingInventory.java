package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 引用 Bukkit 容器的 RootInventory 实现: Bukkit 容器是外部数据来源, 本类只维护一份 Bukkit 内容镜像.
 * <p><strong>线程安全由调用方负责.</strong> 工厂构造, {@link #refresh()} 与所有写操作都会直接访问
 * 被引用的 Bukkit 容器. 调用方必须保证当前执行上下文可以合法访问该容器, 且同一笔事务里的所有
 * ReferencingInventory 都能在该上下文访问. 本类不判断平台或容器的 Folia 执行所有者, 也不调度到其线程,
 * 还不提供只读回退. 平台抛出的线程访问异常会沿调用栈传播; 异常可能发生在 Sparrow 内部状态已经提交
 * 或 Bukkit 容器已经部分写入之后, 因此不能根据异常推断本次操作为零变更.
 * <p>读操作只读取 Bukkit 内容镜像, 不访问 Bukkit 容器, 但这份内容镜像可能滞后, 要等下一次同步才更新.
 * <p>写路径靠两个函数接入事务流程: {@code prepareWrite} 在任何写入口读取规划内容之前
 * 同步容器内容, {@code afterCommit} 在提交成功后, post 事件派发前把变更写回容器.
 * 外部世界(漏斗, 其他插件)对容器的直接修改在同步时被发现, 以 {@link UpdateReason.External}
 * 原因只派发 post 事件.
 * <p>Window 每个 tick 调用一次 {@link #refresh()}; 本类自己不注册调度任务.
 */
@ApiStatus.Experimental
public final class ReferencingInventory extends RootInventory {
    private final Inventory bukkitInventory; // 被引用的 Bukkit 容器, 即外部数据来源
    private final Function<Inventory, @Nullable ItemStack[]> contentsGetter; // 从容器读取被引用区段(getContents / getStorageContents)
    private final SlotKey.ExternalSlot[] externalSlots; // 当前 Inventory 槽位 -> Bukkit 容器槽位, 同步与写回共用
    private final int bukkitMaxStackSize;           // 容器的堆叠上限, 构造时缓存
    private final @Nullable SlotOrder addOrder;     // 玩家存储区的 ADD 顺序按原版 quick-move 反向遍历, 其余情况为 null

    /**
     * 以给定容器与初始 Bukkit 内容镜像创建 ReferencingInventory.
     *
     * @param bukkitInventory 被引用的 Bukkit 容器
     * @param contentsGetter 从容器读取被引用区段的函数
     * @param initialMirror 初始 Bukkit 内容镜像, 已按当前 Inventory 槽位排列, 空物品已转为 {@code null}
     * @param slotMapping 当前 Inventory 槽位到 Bukkit 容器槽位的映射
     * @param addOrder ADD 类别的遍历顺序, {@code null} 回退自然顺序
     */
    private ReferencingInventory(
            Inventory bukkitInventory,
            Function<Inventory, @Nullable ItemStack[]> contentsGetter,
            @Nullable ItemStack[] initialMirror,
            SlotOrder slotMapping,
            @Nullable SlotOrder addOrder
    ) {
        super(initialMirror);
        this.bukkitInventory = bukkitInventory;
        this.contentsGetter = contentsGetter;
        this.externalSlots = externalSlots(bukkitInventory, slotMapping);
        this.bukkitMaxStackSize = bukkitInventory.getMaxStackSize();
        this.addOrder = addOrder;
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
                addOrder
        );
    }

    /**
     * 返回被引用的 Bukkit 容器.
     *
     * @return 被引用的容器
     */
    @NotNull
    public Inventory referencedInventory() {
        return this.bukkitInventory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void refresh() {
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
     * <p>返回 Bukkit 容器槽对应的 SlotKey: 两个 ReferencingInventory 指向同一外部容器身份和同一 Bukkit 容器槽位时, SlotKey 相同.
     */
    @Override
    @NotNull
    SlotKey rootPhysicalKey(@NotNull SlotKey.Anchor anchor) {
        return this.externalSlots[anchor.rootSlot()];
    }

    /**
     * {@inheritDoc}
     *
     * <p>先把 Bukkit 容器当前内容同步进 Bukkit 内容镜像, 再基于更新后的内容规划.
     */
    @Override
    void prepareWrite() {
        this.reconcileFromBukkit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>把每个槽位变更写回对应的 Bukkit 容器槽位.
     */
    @Override
    void afterCommit(@NotNull List<SlotChange> deltas) {
        // SlotChange 的访问器返回物品副本, 容器不会拿到 Bukkit 内容镜像内部的实例
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            this.bukkitInventory.setItem(this.externalSlots[delta.slot()].slot(), delta.after());
        }
    }

    /**
     * 把 Bukkit 容器当前内容和 Bukkit 内容镜像逐槽对比, 再以 External 原因提交差异槽以更新 Bukkit 内容镜像(绕过 pre, 只派发 post).
     * 调用方保证运行期访问被正确串行化, 因此提交被拒绝说明调用边界被破坏, 交给统一异常处理器上报.
     */
    private void reconcileFromBukkit() {
        // 逐槽对比: 比较阶段直接使用容器读出的引用, 不复制物品 —— 绝大多数 tick 没有外部变更,
        // 只有差异槽才由 SlotChange 复制物品
        @Nullable ItemStack[] raw = this.contentsGetter.apply(this.bukkitInventory);
        @Nullable ItemStack[] mirror = this.currentState();
        @Nullable List<SlotChange> deltas = null;
        for (int slot = 0; slot < mirror.length; slot++) {
            @Nullable ItemStack liveItem = raw[this.externalSlots[slot].slot()];
            @Nullable ItemStack mirrorItem = mirror[slot];
            boolean equal = ItemUtils.isNullOrEmpty(liveItem) ? mirrorItem == null : liveItem.equals(mirrorItem);
            if (!equal) {
                if (deltas == null) {
                    deltas = new ArrayList<>();
                }
                deltas.add(new SlotChange(slot, mirrorItem, liveItem));
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
        // 冲突在调用方保证的串行访问下不该发生, 视为调用边界被破坏并上报
        if (!(result instanceof TransactionResult.Committed)) {
            SparrowUI.getInstance().handleException(
                    "Failed to reconcile ReferencingInventory mirror",
                    new IllegalStateException("reconcile commit was rejected: " + result)
            );
        }
    }

    /**
     * 按当前 Inventory 槽位顺序从容器原始内容取样, 复制成 Bukkit 内容镜像(空槽为 {@code null}).
     *
     * @param raw 容器原始内容
     * @param slotMapping 当前 Inventory 槽位到 Bukkit 容器槽位的映射
     * @return 按当前 Inventory 槽位排列的 Bukkit 内容镜像
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
     * 为每个当前 Inventory 槽位建立对应的 Bukkit 容器槽身份.
     *
     * @param inventory 被引用的容器
     * @param slotMapping 当前 Inventory 槽位到 Bukkit 容器槽位的映射
     * @return 每个当前 Inventory 槽位的 SlotKey
     */
    private static SlotKey.ExternalSlot[] externalSlots(Inventory inventory, SlotOrder slotMapping) {
        SlotKey.ExternalSlot[] externalSlots = new SlotKey.ExternalSlot[slotMapping.size()];
        for (int slot = 0; slot < slotMapping.size(); slot++) {
            externalSlots[slot] = new SlotKey.ExternalSlot(inventory, slotMapping.slotAt(slot));
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
