package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.RootInventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 一笔事务中单个 RootInventory 的变更组.
 *
 * @param inventory 发生变更的 RootInventory
 * @param slotChanges 使用该 RootInventory 槽位坐标的变更记录, 不可变列表
 */
public record RootInventoryChange(
        @NotNull RootInventory inventory,
        @NotNull List<SlotChange> slotChanges
) {

    public RootInventoryChange {
        slotChanges = List.copyOf(slotChanges);
    }

    /**
     * 返回满足给定条件的 RootInventory 槽位变更.
     * <p>例如 {@code slotChanges(SlotChange::isAdd)} 返回所有存在物品流入的槽位变更,
     * {@code slotChanges(SlotChange::isRemoveOnly)} 返回只有物品流出的槽位变更.
     *
     * @param filter 变更需要满足的条件
     * @return 使用当前 RootInventory 槽位坐标的不可修改变更列表
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
