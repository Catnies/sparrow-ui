package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inventory 在事务提交前发出的更新事件.
 * <p>{@link #slotChanges()} 使用当前订阅 Inventory 的槽位坐标,
 * {@link #rootChanges()} 则保留整笔事务涉及的所有 RootInventory 变更.
 */
public final class InventoryPreUpdateEvent extends InventoryUpdateEvent {
    private final ItemStack[][] plannedStates;           // 与 rootChanges 一一对应的规划基准状态
    private final SparrowInventory[] rootsBySlot;        // 当前 Inventory 逻辑槽位对应的 RootInventory
    private final int[] rootSlotsBySlot;                 // 当前 Inventory 逻辑槽位对应的 RootInventory 槽位
    private volatile boolean cancelled;                  // 是否已经有处理器取消整笔事务

    /**
     * 创建一个提交前更新事件.
     *
     * @param inventory 当前事件使用其逻辑槽位坐标的 Inventory
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
        this(inventory, reason, slotChanges, rootChanges, new ItemStack[rootChanges.size()][], new SparrowInventory[inventory.size()], new int[inventory.size()]);
    }

    /**
     * 创建一个可以在当前同步处理器内修改候选最终值的提交前事件.
     *
     * @param inventory 当前事件使用其逻辑槽位坐标的 Inventory
     * @param reason 事务触发原因
     * @param slotChanges 投影到当前订阅 Inventory 后的槽位变更
     * @param rootChanges 整笔事务的完整 RootInventory 变更
     * @param plannedStates 每个参与 RootInventory 的规划基准状态
     * @param rootsBySlot 当前 Inventory 每个逻辑槽位对应的 RootInventory
     * @param rootSlotsBySlot 当前 Inventory 每个逻辑槽位对应的 RootInventory 槽位
     */
    @ApiStatus.Internal
    public InventoryPreUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges,
            ItemStack @NotNull [] @NotNull [] plannedStates,
            SparrowInventory @NotNull [] rootsBySlot,
            int @NotNull [] rootSlotsBySlot
    ) {
        super(inventory, reason, slotChanges, rootChanges);
        if (plannedStates.length != rootChanges.size())
            throw new IllegalArgumentException("planned state count does not match root change count");
        if (rootsBySlot.length != inventory.size() || rootSlotsBySlot.length != inventory.size())
            throw new IllegalArgumentException("logical root mapping size does not match inventory size");

        this.plannedStates = plannedStates;
        this.rootsBySlot = rootsBySlot;
        this.rootSlotsBySlot = rootSlotsBySlot;
    }

    /**
     * 使用当前 {@link #inventory()} 的逻辑槽位坐标重写候选最终值.
     * <p>该槽位映射到的 RootInventory 必须已经参与本次事务.
     *
     * @param slot 当前 Inventory 的逻辑槽位
     * @param after 新的候选最终值, {@code null} 表示清空槽位
     * @throws IndexOutOfBoundsException 当前 Inventory 不包含该槽位
     * @throws IllegalArgumentException 该槽位对应的 RootInventory 未参与本次事务
     * @throws IllegalStateException 当前同步处理器已经退出, 或从其他线程调用
     */
    public void setAfter(int slot, @Nullable ItemStack after) {
        Objects.checkIndex(slot, this.rootsBySlot.length);
        this.setRootAfter(this.rootsBySlot[slot], this.rootSlotsBySlot[slot], after);
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
    public void setAfter(@NotNull SparrowInventory rootInventory, int rootSlot, @Nullable ItemStack after) {
        this.setRootAfter(Objects.requireNonNull(rootInventory, "rootInventory"), rootSlot, after);
    }

    private void setRootAfter(@NotNull SparrowInventory rootInventory, int rootSlot, @Nullable ItemStack after) {
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

        List<RootInventoryChange> rewrittenRoots = new ArrayList<>(rootChanges);
        rewrittenRoots.set(rootIndex, new RootInventoryChange(rootInventory, updated));
        List<RootInventoryChange> immutableRoots = List.copyOf(rewrittenRoots);
        this.replaceChanges(this.project(immutableRoots), immutableRoots);
    }

    @NotNull
    private List<SlotChange> project(@NotNull List<RootInventoryChange> rootChanges) {
        List<SlotChange> projected = new ArrayList<>();
        for (int i = 0; i < rootChanges.size(); i++) {
            RootInventoryChange rootChange = rootChanges.get(i);
            List<SlotChange> rootSlots = rootChange.slotChanges();
            for (int j = 0; j < rootSlots.size(); j++) {
                SlotChange rootSlot = rootSlots.get(j);
                for (int logicalSlot = 0; logicalSlot < this.rootsBySlot.length; logicalSlot++) {
                    if (this.rootsBySlot[logicalSlot] == rootChange.inventory()
                            && this.rootSlotsBySlot[logicalSlot] == rootSlot.slot()) {
                        projected.add(rootSlot.withSlot(logicalSlot));
                        break;
                    }
                }
            }
        }
        return projected.isEmpty() ? List.of() : List.copyOf(projected);
    }

    /**
     * 取消整笔事务.
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
