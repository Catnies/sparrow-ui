package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 一笔事务中单个 Inventory 的变更组.
 *
 * @param inventory 发生变更的 Inventory
 * @param slotChanges 使用该 Inventory 槽位坐标的变更记录, 不可变列表
 */
public record InventoryChange(
        @NotNull SparrowInventory inventory,
        @NotNull List<SlotChange> slotChanges
) {

    public InventoryChange {
        slotChanges = List.copyOf(slotChanges);
    }

    /**
     * 从整笔事务的变更组里挑出属于某个 Inventory 的那一组变更.
     *
     * @param inventory 要查找的 Inventory
     * @param rootChanges 整笔事务的 Inventory 变更组
     * @return 该 Inventory 的变更组; 它没有参与这笔事务时返回一个不含任何槽位变更的空变更组
     */
    @NotNull
    public static InventoryChange changeOf(
            @NotNull SparrowInventory inventory,
            @NotNull List<InventoryChange> rootChanges
    ) {
        for (int i = 0; i < rootChanges.size(); i++) {
            InventoryChange rootChange = rootChanges.get(i);
            if (rootChange.inventory() == inventory) {
                return rootChange;
            }
        }
        return new InventoryChange(inventory, List.of());
    }

    /**
     * 从整笔事务的变更组里挑出属于某个 Inventory 的那一组槽位变更.
     *
     * @param inventory 要查找的 Inventory
     * @param rootChanges 整笔事务的 Inventory 变更组
     * @return 使用该 Inventory 槽位坐标的变更列表; 它没有参与这笔事务时返回空列表
     */
    @NotNull
    public static List<SlotChange> slotChangesOf(
            @NotNull SparrowInventory inventory,
            @NotNull List<InventoryChange> rootChanges
    ) {
        return changeOf(inventory, rootChanges).slotChanges();
    }

    /**
     * 返回指定逻辑槽位的变更记录.
     *
     * @param slot 当前 Inventory 的逻辑槽位
     * @return 槽位未参与本次事务时返回 {@code null}
     * @throws IndexOutOfBoundsException 槽位不属于当前 Inventory 时抛出
     */
    @Nullable
    public SlotChange changeAt(int slot) {
        Objects.checkIndex(slot, this.inventory.size());
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (change.slot() == slot) {
                return change;
            }
        }
        return null;
    }

    /**
     * 返回满足给定条件的 Inventory 槽位变更.
     * <p>例如 {@code slotChanges(SlotChange::isAdd)} 返回所有存在物品流入的槽位变更,
     * {@code slotChanges(SlotChange::isRemoveOnly)} 返回只有物品流出的槽位变更.
     *
     * @param filter 变更需要满足的条件
     * @return 使用当前 Inventory 槽位坐标的不可修改变更列表
     */
    @NotNull
    public List<SlotChange> slotChanges(@NotNull Predicate<? super SlotChange> filter) {
        List<SlotChange> matches = new ArrayList<>();
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (filter.test(change)) {
                matches.add(change);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 判断当前 Inventory 是否只有物品流入.
     * <p>至少需要一个槽位存在物品流入, 且不能有任何槽位存在物品流出.
     * 内容没有变化的槽位不影响判断.
     *
     * @return 是否只有物品流入
     */
    public boolean isAddOnly() {
        boolean hasAdd = false;
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (change.isRemove()) {
                return false;
            }
            if (change.isAdd()) {
                hasAdd = true;
            }
        }
        return hasAdd;
    }

    /**
     * 判断当前 Inventory 是否只有物品流出.
     * <p>至少需要一个槽位存在物品流出, 且不能有任何槽位存在物品流入.
     * 内容没有变化的槽位不影响判断.
     *
     * @return 是否只有物品流出
     */
    public boolean isRemoveOnly() {
        boolean hasRemove = false;
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (change.isAdd()) {
                return false;
            }
            if (change.isRemove()) {
                hasRemove = true;
            }
        }
        return hasRemove;
    }
}
