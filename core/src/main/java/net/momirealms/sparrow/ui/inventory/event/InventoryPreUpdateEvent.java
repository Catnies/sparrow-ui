package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.InventoryTopology;
import net.momirealms.sparrow.ui.inventory.RootInventory;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Inventory 在事务提交前发出的更新事件.
 * <p>{@link #slotChanges()} 使用当前订阅 Inventory 的槽位坐标,
 * {@link #rootChanges()} 则保留整笔事务涉及的所有 RootInventory 变更.
 * <p>编辑窗口只覆盖当前同步处理器: 处理器返回后事件立即失效,
 * 任何线程再调用 {@link #setAfter} 都会失败.
 */
public final class InventoryPreUpdateEvent extends InventoryUpdateEvent {
    private final ItemStack[][] plannedStates;          // 与 rootChanges 一一对应的规划基准状态
    @Nullable private final InventoryTopology topology; // 当前 Inventory 的槽位映射表, null 表示事件不支持编辑
    private final Thread handlerThread;                 // 创建事件的处理器线程, setAfter 只允许它调用
    private volatile boolean editable;                  // 编辑窗口是否仍然打开
    private volatile boolean cancelled;                 // 是否已经有处理器取消整笔事务

    /**
     * 创建一个不支持编辑候选最终值的提交前事件.
     * <p>调用 {@link #setAfter} 会直接失败; 查询和取消事务仍然可用.
     *
     * @param inventory 当前事件使用其槽位坐标的 Inventory
     * @param reason 事务触发原因
     * @param slotChanges 投影到当前订阅 Inventory 后的槽位变更
     * @param rootChanges 整笔事务的完整 RootInventory 变更
     */
    @ApiStatus.Internal
    public InventoryPreUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges
    ) {
        this(inventory, reason, slotChanges, rootChanges, new ItemStack[rootChanges.size()][], null);
    }

    /**
     * 创建一个可以在当前同步处理器内修改候选最终值的提交前事件.
     *
     * @param inventory 当前事件使用其槽位坐标的 Inventory
     * @param reason 事务触发原因
     * @param slotChanges 投影到当前订阅 Inventory 后的槽位变更
     * @param rootChanges 整笔事务的完整 RootInventory 变更
     * @param plannedStates 每个参与 RootInventory 的规划基准状态
     * @param topology 当前 Inventory 的槽位映射表; 为 {@code null} 时事件不支持编辑
     */
    @ApiStatus.Internal
    public InventoryPreUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges,
            ItemStack @NotNull [] @NotNull [] plannedStates,
            @Nullable InventoryTopology topology
    ) {
        super(inventory, reason, slotChanges, rootChanges);
        if (plannedStates.length != rootChanges.size()) {
            throw new IllegalArgumentException("planned state count does not match root change count");
        }
        this.plannedStates = plannedStates;
        this.topology = topology;
        // 事件在派发方线程上构造后立即交给处理器, 构造线程就是处理器线程.
        this.handlerThread = Thread.currentThread();
        this.editable = topology != null;
    }

    /**
     * 使用当前 {@link #inventory()} 的槽位坐标重写候选最终值.
     * <p>该槽位映射到的 RootInventory 必须已经参与本次事务.
     *
     * @param slot 当前 Inventory 的槽位
     * @param after 新的候选最终值, {@code null} 表示清空槽位
     * @throws IndexOutOfBoundsException 当前 Inventory 不包含该槽位
     * @throws IllegalArgumentException 该槽位对应的 RootInventory 未参与本次事务
     * @throws IllegalStateException 当前同步处理器已经退出, 或从其他线程调用
     */
    public void setAfter(int slot, @Nullable ItemStack after) {
        InventoryTopology topology = this.editableTopology();
        this.setRootAfter(topology.rootOf(slot), topology.rootSlotOf(slot), after);
    }

    /**
     * 使用 RootInventory 槽位坐标重写候选最终值.
     * <p>{@code rootInventory} 必须是当前 {@link #rootChanges()} 中某个变更组的 {@link RootInventoryChange#inventory()}
     * 返回的同一实例. 可以修改该 RootInventory 内原事务没有写到的槽位, 但不能引入新的 RootInventory.
     *
     * @param rootInventory 本次事务已经参与的 RootInventory
     * @param rootSlot RootInventory 槽位
     * @param after 新的候选最终值, {@code null} 表示清空槽位
     * @throws NullPointerException rootInventory 为 {@code null}
     * @throws IndexOutOfBoundsException RootInventory 不包含该槽位
     * @throws IllegalArgumentException 传入的 Inventory 不是本次事务的参与 RootInventory
     * @throws IllegalStateException 当前同步处理器已经退出, 或从其他线程调用
     */
    public void setAfter(@NotNull RootInventory rootInventory, int rootSlot, @Nullable ItemStack after) {
        this.setRootAfter(Objects.requireNonNull(rootInventory, "rootInventory"), rootSlot, after);
    }

    // 在编辑窗口内重写指定 RootInventory 槽位的候选最终值, 两个 {@link #setAfter} 重载共用.
    private void setRootAfter(@NotNull RootInventory rootInventory, int rootSlot, @Nullable ItemStack after) {
        InventoryTopology topology = this.editableTopology();

        // 找出该 RootInventory 在本次事务中的位置, 不允许引入新的 RootInventory.
        List<RootInventoryChange> rootChanges = this.rootChanges();
        int rootIndex = -1;
        for (int i = 0; i < rootChanges.size(); i++) {
            if (rootChanges.get(i).inventory() == rootInventory) {
                rootIndex = i;
                break;
            }
        }
        if (rootIndex == -1) {
            throw new IllegalArgumentException("inventory is not a participating RootInventory");
        }

        // 该槽位已有变更时替换其候选最终值并保留原 before, 否则以规划基准状态为 before 追加新变更.
        @Nullable ItemStack[] planned = this.plannedStates[rootIndex];
        Objects.checkIndex(rootSlot, planned.length);
        RootInventoryChange rootChange = rootChanges.get(rootIndex);
        List<SlotChange> current = rootChange.slotChanges();
        List<SlotChange> updated = new ArrayList<>(current.size() + 1);
        boolean replaced = false;
        for (int i = 0; i < current.size(); i++) {
            SlotChange change = current.get(i);
            if (change.slot() == rootSlot) {
                updated.add(new SlotChange(rootSlot, change.rawBefore(), after));
                replaced = true;
            } else {
                updated.add(change);
            }
        }
        if (!replaced) {
            updated.add(new SlotChange(rootSlot, planned[rootSlot], after));
        }

        // 用重写后的变更组替换事件快照, 当前 Inventory 的槽位变更按映射表重新投影.
        List<RootInventoryChange> rewrittenRoots = new ArrayList<>(rootChanges);
        rewrittenRoots.set(rootIndex, new RootInventoryChange(rootInventory, updated));
        List<RootInventoryChange> immutableRoots = List.copyOf(rewrittenRoots);
        this.replaceChanges(topology.project(immutableRoots), immutableRoots);
    }

    // 校验编辑窗口仍然打开, 且调用方就是创建事件的处理器线程.
    @NotNull
    private InventoryTopology editableTopology() {
        InventoryTopology topology = this.topology;
        if (topology == null || !this.editable || Thread.currentThread() != this.handlerThread) {
            throw new IllegalStateException("pre-update event can only be edited inside its synchronous handler");
        }
        return topology;
    }

    /**
     * 关闭编辑窗口, 由事件派发方在处理器返回后调用.
     * <p>关闭后任何 {@link #setAfter} 调用都会失败, 防止逃逸出去的事件引用继续修改事务.
     */
    @ApiStatus.Internal
    public void closeEditing() {
        this.editable = false;
    }

    /**
     * 取消整笔事务, 或恢复前面处理器留下的取消.
     * <p>取消状态按订阅顺序在处理器之间传递: 当前处理器看到的初始值就是前面处理器留下的结果,
     * 传入 {@code false} 会清除这个取消, 事务照常提交.
     * <p>当前处理器抛出异常时, 这次调用连同它通过 {@link #setAfter} 做的候选值修改一起被丢弃,
     * 后面的处理器看到的仍然是它执行前的取消状态.
     *
     * @param cancelled {@code true} 取消整笔事务, {@code false} 让事务继续提交
     */
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * 返回当前取消状态.
     *
     * @return 当前事务是否会被取消
     */
    public boolean cancelled() {
        return this.cancelled;
    }
}
