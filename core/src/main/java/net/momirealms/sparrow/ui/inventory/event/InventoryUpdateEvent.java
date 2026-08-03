package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Inventory 在一笔事务中的公共更新数据.
 * <p>{@link #slotChanges()} 使用当前订阅 Inventory 的逻辑槽位坐标,
 * {@link #rootChanges()} 则保留整笔事务涉及的所有 RootInventory 变更.
 */
public abstract class InventoryUpdateEvent {
    private final SparrowInventory inventory;              // 当前事件使用其逻辑槽位坐标的 Inventory
    private final UpdateReason reason;                     // 整笔事务的触发原因
    private volatile List<SlotChange> slotChanges;            // 投影到当前 Inventory 后的槽位变更
    private volatile List<RootInventoryChange> rootChanges;   // 整笔事务的完整 RootInventory 变更

    @Nullable private volatile NetItems netItems;          // 第一次查询净变化时计算

    InventoryUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges
    ) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.slotChanges = List.copyOf(slotChanges);
        this.rootChanges = List.copyOf(rootChanges);
    }

    /**
     * 替换当前事件展示的候选事务快照.
     * <p>只有提交前事件会在其同步回调期间调用本方法; 提交后事件的快照始终固定.
     *
     * @param slotChanges 投影到当前 Inventory 后的槽位变更
     * @param rootChanges 整笔事务的完整 RootInventory 变更
     */
    final synchronized void replaceChanges(
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges
    ) {
        this.slotChanges = List.copyOf(slotChanges);
        this.rootChanges = List.copyOf(rootChanges);
        this.netItems = null;
    }

    /**
     * 返回本事件使用其逻辑槽位坐标的 Inventory.
     *
     * @return 当前订阅的 Inventory
     */
    @NotNull
    public final SparrowInventory inventory() {
        return this.inventory;
    }

    /**
     * 返回本次事务的触发原因.
     *
     * @return 事务触发原因
     */
    @NotNull
    public final UpdateReason reason() {
        return this.reason;
    }

    /**
     * 返回投影到当前订阅 Inventory 后的槽位变更.
     *
     * @return 使用当前 Inventory 槽位坐标的变更记录
     */
    @NotNull
    public final List<SlotChange> slotChanges() {
        return this.slotChanges;
    }

    /**
     * 返回满足给定条件的槽位变更.
     * <p>例如 {@code slotChanges(SlotChange::isAdd)} 返回所有存在物品流入的槽位变更,
     * {@code slotChanges(SlotChange::isRemoveOnly)} 返回只有物品流出的槽位变更.
     *
     * @param filter 变更需要满足的条件
     * @return 使用当前 Inventory 槽位坐标的不可修改变更列表
     */
    @NotNull
    public final List<SlotChange> slotChanges(@NotNull Predicate<? super SlotChange> filter) {
        // 先抓 volatile 引用, 避免过滤途中候选快照被替换造成前后不一致.
        List<SlotChange> slotChanges = this.slotChanges;
        List<SlotChange> matches = new ArrayList<>();
        for (int i = 0; i < slotChanges.size(); i++) {
            SlotChange change = slotChanges.get(i);
            if (filter.test(change)) {
                matches.add(change);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 返回指定逻辑槽位的变更记录.
     *
     * @param slot 当前 Inventory 的逻辑槽位
     * @return 槽位未参与本次事务时返回 {@code null}
     * @throws IndexOutOfBoundsException 槽位不属于当前 Inventory 时抛出
     */
    @Nullable
    public final SlotChange changeAt(int slot) {
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
     * 判断当前 Inventory 是否只有物品流入.
     * <p>至少需要一个槽位存在物品流入, 且不能有任何槽位存在物品流出.
     * 内容没有变化的槽位不影响判断.
     *
     * @return 是否只有物品流入
     */
    public final boolean isAddOnly() {
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
    public final boolean isRemoveOnly() {
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

    /**
     * 返回本次变更对当前 Inventory 内容产生的净变化类型.
     *
     * @return 当前 Inventory 的净变化类型
     */
    @NotNull
    public final InventoryNetChange netChange() {
        return this.netItems().change();
    }

    /**
     * 返回本次变更使当前 Inventory 净增加的物品.
     * 相似物品在不同槽位中的增加和移除会相互抵消.
     *
     * @return 不可修改的独立物品副本列表
     */
    @NotNull
    public final List<ItemStack> netAddedItems() {
        return copyItems(this.netItems().addedItems());
    }

    /**
     * 返回本次变更使当前 Inventory 净移除的物品.
     * 相似物品在不同槽位中的增加和移除会相互抵消.
     *
     * @return 不可修改的独立物品副本列表
     */
    @NotNull
    public final List<ItemStack> netRemovedItems() {
        return copyItems(this.netItems().removedItems());
    }

    /**
     * 返回整笔事务涉及的所有 RootInventory 变更组.
     *
     * @return 使用 RootInventory 槽位坐标的完整事务变更
     */
    @NotNull
    public final List<RootInventoryChange> rootChanges() {
        return this.rootChanges;
    }

    @NotNull
    private NetItems netItems() {
        NetItems netItems = this.netItems;
        if (netItems == null) {
            synchronized (this) {
                netItems = this.netItems;
                if (netItems == null) {
                    netItems = calculateNetItems(this.slotChanges);
                    this.netItems = netItems;
                }
            }
        }
        return netItems;
    }

    @NotNull
    private static NetItems calculateNetItems(@NotNull List<SlotChange> slotChanges) {
        List<NetItem> addedItems = new ArrayList<>();
        List<NetItem> removedItems = new ArrayList<>();
        for (int i = 0; i < slotChanges.size(); i++) {
            SlotChange change = slotChanges.get(i);
            int removedAmount = change.removedAmount();
            ItemStack before = change.unsafeBefore();
            if (removedAmount > 0 && before != null) {
                balance(removedItems, addedItems, before, removedAmount);
            }
            int addedAmount = change.addedAmount();
            ItemStack after = change.unsafeAfter();
            if (addedAmount > 0 && after != null) {
                balance(addedItems, removedItems, after, addedAmount);
            }
        }

        InventoryNetChange change;
        if (addedItems.isEmpty()) {
            change = removedItems.isEmpty() ? InventoryNetChange.NONE : InventoryNetChange.REMOVAL;
        } else {
            change = removedItems.isEmpty() ? InventoryNetChange.ADDITION : InventoryNetChange.MIXED;
        }
        return new NetItems(change, List.copyOf(addedItems), List.copyOf(removedItems));
    }

    private static void balance(@NotNull List<NetItem> sameDirection, @NotNull List<NetItem> oppositeDirection, @NotNull ItemStack template, int amount) {
        int remaining = amount;
        for (int i = 0; i < oppositeDirection.size() && remaining > 0; ) {
            NetItem opposite = oppositeDirection.get(i);
            if (!template.isSimilar(opposite.template())) {
                i++;
                continue;
            }
            int matched = Math.min(remaining, opposite.amount());
            remaining -= matched;
            if (matched == opposite.amount()) {
                oppositeDirection.remove(i);
            } else {
                oppositeDirection.set(i, new NetItem(opposite.template(), opposite.amount() - matched));
                i++;
            }
        }
        for (int i = 0; i < sameDirection.size() && remaining > 0; i++) {
            NetItem same = sameDirection.get(i);
            if (!template.isSimilar(same.template())) {
                continue;
            }
            int accepted = Math.min(remaining, same.template().getMaxStackSize() - same.amount());
            if (accepted > 0) {
                sameDirection.set(i, new NetItem(same.template(), same.amount() + accepted));
                remaining -= accepted;
            }
        }
        while (remaining > 0) {
            int stackAmount = Math.min(remaining, template.getMaxStackSize());
            sameDirection.add(new NetItem(template, stackAmount));
            remaining -= stackAmount;
        }
    }

    @NotNull
    private static List<ItemStack> copyItems(@NotNull List<NetItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<ItemStack> copies = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            NetItem item = items.get(i);
            copies.add(ItemUtils.copyWithAmount(item.template(), item.amount()));
        }
        return List.copyOf(copies);
    }

    private record NetItem(@NotNull ItemStack template, int amount) {
    }

    private record NetItems(
            @NotNull InventoryNetChange change,
            @NotNull List<NetItem> addedItems,
            @NotNull List<NetItem> removedItems
    ) {
    }
}
