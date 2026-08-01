package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 一笔事务中单个 RootInventory 的变更组.
 *
 * @param inventory 发生变更的 RootInventory
 * @param slotChanges 使用该 RootInventory 槽位坐标的变更记录, 不可变列表
 */
public record RootInventoryChange(
        @NotNull SparrowInventory inventory,
        @NotNull List<SlotChange> slotChanges
) {

    public RootInventoryChange {
        slotChanges = List.copyOf(slotChanges);
    }

    /**
     * 返回存在物品流入的 RootInventory 槽位变更.
     * <p>替换同时存在物品流入与流出, 因此对应槽位也会包含在本列表中.
     *
     * @return 使用当前 RootInventory 槽位坐标的不可修改变更列表
     */
    @NotNull
    public List<SlotChange> addChangeSlots() {
        List<SlotChange> matches = new ArrayList<>();
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (change.isAdd()) {
                matches.add(change);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 返回只有物品流入而没有物品流出的 RootInventory 槽位变更.
     * <p>不相似物品替换同时存在物品流入与流出, 因此不会包含在本列表中.
     *
     * @return 使用当前 RootInventory 槽位坐标的不可修改变更列表
     */
    @NotNull
    public List<SlotChange> addOnlySlots() {
        List<SlotChange> matches = new ArrayList<>();
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (change.isAddOnly()) {
                matches.add(change);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 返回存在物品流出的 RootInventory 槽位变更.
     * <p>替换同时存在物品流入与流出, 因此对应槽位也会包含在本列表中.
     *
     * @return 使用当前 RootInventory 槽位坐标的不可修改变更列表
     */
    @NotNull
    public List<SlotChange> removeChangeSlots() {
        List<SlotChange> matches = new ArrayList<>();
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (change.isRemove()) {
                matches.add(change);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 返回只有物品流出而没有物品流入的 RootInventory 槽位变更.
     * <p>不相似物品替换同时存在物品流入与流出, 因此不会包含在本列表中.
     *
     * @return 使用当前 RootInventory 槽位坐标的不可修改变更列表
     */
    @NotNull
    public List<SlotChange> removeOnlySlots() {
        List<SlotChange> matches = new ArrayList<>();
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (change.isRemoveOnly()) {
                matches.add(change);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 返回显式写入前后内容没有变化的 RootInventory 槽位变更.
     *
     * @return 使用当前 RootInventory 槽位坐标的不可修改变更列表
     */
    @NotNull
    public List<SlotChange> unchangedSlots() {
        List<SlotChange> matches = new ArrayList<>();
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (change.isUnchanged()) {
                matches.add(change);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 返回发生不相似物品替换的 RootInventory 槽位变更.
     *
     * @return 使用当前 RootInventory 槽位坐标的不可修改变更列表
     */
    @NotNull
    public List<SlotChange> replacementChangeSlots() {
        List<SlotChange> matches = new ArrayList<>();
        for (int i = 0; i < this.slotChanges.size(); i++) {
            SlotChange change = this.slotChanges.get(i);
            if (change.isReplacement()) {
                matches.add(change);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 判断当前 RootInventory 是否只有物品流入.
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
     * 判断当前 RootInventory 是否只有物品流出.
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
