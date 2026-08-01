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
 * 引用真实 Bukkit 容器的 Inventory 实现: 容器是真实数据的所在地, 本类只维护它的一份镜像快照.
 * <p><strong>线程安全由调用方负责.</strong> 工厂构造、{@link #refresh()} 与所有写操作都会直接访问
 * 被引用的 Bukkit 容器. 调用方必须保证当前执行上下文可以合法访问该容器, 且同一笔事务里的所有
 * ReferencingInventory 都能在该上下文访问. 本类不判断平台或容器 owner, 不调度到 owner 线程,
 * 也不提供只读回退. 平台抛出的线程访问异常会沿调用栈传播; 异常可能发生在 Sparrow 镜像已经提交
 * 或 Bukkit 容器已经部分写入之后, 因此不能根据异常推断本次操作为零变更.
 * <p>读操作只读取镜像, 不访问 Bukkit 容器, 但内容可能滞后于容器, 要等下一次同步才更新.
 * <p>写路径靠两个函数接入事务流程: {@code prepareWrite} 在任何写入口读取规划快照之前
 * 同步容器内容, {@code afterCommit} 在提交成功后, post 事件派发前把变更写回容器.
 * 外部世界(漏斗, 其他插件)对容器的直接修改在同步时被发现, 以 {@link UpdateReason.External}
 * 原因只派发 post 事件.
 * <p>Window 每个 tick 调用一次 {@link #refresh()}; 本类自己不注册调度任务.
 */
@ApiStatus.Experimental
public final class ReferencingInventory extends RootInventory {
    private final Inventory bukkitInventory; // 被引用的 Bukkit 容器, 真实数据所在地
    private final Function<Inventory, @Nullable ItemStack[]> contentsGetter; // 从容器读取被引用区段(getContents / getStorageContents)
    private final SlotKey.ExternalSlot[] externalSlots; // 逻辑槽 -> 容器里的真实槽位, 同步与写回共用
    private final int bukkitMaxStackSize;           // 容器的堆叠上限, 构造时缓存
    private final @Nullable SlotOrder addOrder;     // 玩家存储区的 ADD 顺序按原版 quick-move 反向遍历, 其余情况为 null

    /**
     * 以给定容器与初始镜像创建 ReferencingInventory.
     *
     * @param bukkitInventory 被引用的 Bukkit 容器
     * @param contentsGetter 从容器读取被引用区段的函数
     * @param initialMirror 初始镜像内容, 已按逻辑槽序排列并归一化
     * @param slotMapping 逻辑槽到容器槽位的映射
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
     * 引用玩家背包的存储内容, 并把热键行挪到最后九个逻辑槽:
     * 逻辑槽 {@code i} 对应真实槽 {@code (i + 9) % 36}, 主背包在前, 快捷栏在后.
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
     * @param slotReorder 逻辑槽到真实槽的重排函数
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
     * <p>先把容器最新内容同步进镜像, 规划才基于最新数据.
     */
    @Override
    void prepareWrite() {
        this.reconcileFromBukkit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>把每个槽位变更写回容器对应的真实槽位.
     */
    @Override
    void afterCommit(@NotNull List<SlotChange> deltas) {
        // delta 的访问器返回克隆, 容器不会拿到镜像内部实例
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            this.bukkitInventory.setItem(this.externalSlots[delta.slot()].slot(), delta.after());
        }
    }

    /**
     * 把容器当前内容和镜像逐槽对比, 差异槽以 External 原因提交进镜像(绕过 pre, 只派发 post).
     * 调用方保证运行期访问被正确串行化, 因此提交被拒绝说明调用边界被破坏, 交给统一异常处理器上报.
     */
    private void reconcileFromBukkit() {
        // 逐槽对比: 比较阶段直接拿容器读出的引用, 不做深克隆 —— 绝大多数 tick 没有外部变更,
        // 只有差异槽才在 SlotDelta 构造里克隆
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
    private static SlotKey.ExternalSlot[] externalSlots(Inventory inventory, SlotOrder slotMapping) {
        SlotKey.ExternalSlot[] externalSlots = new SlotKey.ExternalSlot[slotMapping.size()];
        for (int slot = 0; slot < slotMapping.size(); slot++) {
            externalSlots[slot] = new SlotKey.ExternalSlot(inventory, slotMapping.slotAt(slot));
        }
        return externalSlots;
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
