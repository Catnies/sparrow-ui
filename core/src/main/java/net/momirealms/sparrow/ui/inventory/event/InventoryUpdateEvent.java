package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Inventory 在一笔事务中的公共更新数据.
 * <p>{@link #slotChanges()} 只含当前订阅 Inventory 自己的槽位变更,
 * {@link #rootChanges()} 则保留整笔事务涉及的所有 Inventory 变更.
 */
public abstract class InventoryUpdateEvent {
    private final SparrowInventory inventory;
    private final UpdateReason reason;
    private volatile List<TransactionScope> scopes;

    // Pre 改写 scopes 时清空以下惰性视图.
    @Nullable private volatile NetItems netItems;
    @Nullable private volatile InventoryChange ownChange;
    @Nullable private volatile List<InventoryChange> rootChanges;

    InventoryUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<TransactionScope> scopes
    ) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.scopes = List.copyOf(scopes);
    }

    // 事务引擎通过当前写集接纳 Pre 处理器的修改.
    @NotNull
    @ApiStatus.Internal
    public final List<TransactionScope> scopes() {
        return this.scopes;
    }

    // 替换当前事件展示的候选事务快照, 只有 PreUpdateEvent 会在其同步回调期间调用本方法
    final synchronized void replaceScopes(@NotNull List<TransactionScope> scopes) {
        this.scopes = List.copyOf(scopes);
        this.rootChanges = null;
        this.ownChange = null;
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
     * 返回本次事务的参与玩家, 如果没有玩家参与则返回 {@code null}.
     *
     * @return 参与事务的玩家
     */
    @Nullable
    public Player player() {
        if (this.reason instanceof PlayerUpdateReason playerUpdateReason) {
            return playerUpdateReason.player();
        }
        return null;
    }

    /**
     * 返回当前订阅 Inventory 自己的槽位变更, 使用它的逻辑槽位坐标.
     *
     * @return 当前 Inventory 的槽位变更记录
     */
    @NotNull
    public final List<SlotChange> slotChanges() {
        return this.ownChange().slotChanges();
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
        return this.ownChange().slotChanges(filter);
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
        return this.ownChange().changeAt(slot);
    }

    /**
     * 判断当前 Inventory 是否有物品流入且没有物品流出.
     *
     * @return 是否只有物品流入
     */
    public final boolean isAddOnly() {
        return this.ownChange().isAddOnly();
    }

    /**
     * 判断当前 Inventory 是否有物品流出且没有物品流入.
     *
     * @return 是否只有物品流出
     */
    public final boolean isRemoveOnly() {
        return this.ownChange().isRemoveOnly();
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
     * 返回整笔事务涉及的所有 Inventory 变更组.
     *
     * @return 使用 Inventory 槽位坐标的完整事务变更
     */
    @NotNull
    public final List<InventoryChange> rootChanges() {
        List<InventoryChange> rootChanges = this.rootChanges;
        if (rootChanges == null) {
            synchronized (this) {
                rootChanges = this.rootChanges;
                if (rootChanges == null) {
                    rootChanges = changesOf(this.scopes);
                    this.rootChanges = rootChanges;
                }
            }
        }
        return rootChanges;
    }

    // 从整笔事务的写集里定位当前 Inventory 自己那一组, 当前 Inventory 没有变化时是一个空变更组.
    @NotNull
    private InventoryChange ownChange() {
        InventoryChange ownChange = this.ownChange;
        if (ownChange == null) {
            synchronized (this) {
                ownChange = this.ownChange;
                if (ownChange == null) {
                    ownChange = ownChangeOf(this.inventory, this.scopes);
                    this.ownChange = ownChange;
                }
            }
        }
        return ownChange;
    }

    // 在写集里按实例找出指定 Inventory 的那一组变更, 它没有参与本次事务时返回一个空变更组.
    @NotNull
    private static InventoryChange ownChangeOf(@NotNull SparrowInventory inventory, @NotNull List<TransactionScope> scopes) {
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            if (scope.inventory() == inventory) {
                return scope.change();
            }
        }
        return new InventoryChange(inventory, List.of());
    }

    // 把整份写集转成面向订阅者的变更视图, 顺序与各个 Inventory 参与事务的顺序一致.
    @NotNull
    private static List<InventoryChange> changesOf(@NotNull List<TransactionScope> scopes) {
        List<InventoryChange> changes = new ArrayList<>(scopes.size());
        for (int i = 0; i < scopes.size(); i++) {
            changes.add(scopes.get(i).change());
        }
        return List.copyOf(changes);
    }

    // 取得当前 Inventory 的净变化, 首次查询时按自己那一组槽位变更算出并缓存.
    @NotNull
    private NetItems netItems() {
        NetItems netItems = this.netItems;
        if (netItems == null) {
            synchronized (this) {
                netItems = this.netItems;
                if (netItems == null) {
                    netItems = calculateNetItems(this.ownChange().slotChanges());
                    this.netItems = netItems;
                }
            }
        }
        return netItems;
    }

    // 同类物品在槽位间移动时, 流入与流出相互抵消.
    @NotNull
    private static NetItems calculateNetItems(@NotNull List<SlotChange> slotChanges) {
        List<NetItem> addedItems = new ArrayList<>();
        List<NetItem> removedItems = new ArrayList<>();
        // 逐个槽位把流出和流入累加进各自方向, 由 balance 负责与反方向已有记录相互抵消.
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
        // 抵消之后两个方向都有剩余, 说明既拿进来又拿出去; 只剩一个方向时才是纯粹的增加或移除.
        InventoryNetChange change;
        if (addedItems.isEmpty()) {
            change = removedItems.isEmpty() ? InventoryNetChange.NONE : InventoryNetChange.REMOVAL;
        } else {
            change = removedItems.isEmpty() ? InventoryNetChange.ADDITION : InventoryNetChange.MIXED;
        }
        return new NetItems(change, List.copyOf(addedItems), List.copyOf(removedItems));
    }

    // 先抵消反方向的同类物品, 再按最大堆叠量记录净余量.
    private static void balance(@NotNull List<NetItem> sameDirection, @NotNull List<NetItem> oppositeDirection, @NotNull ItemStack template, int amount) {
        int remaining = amount;
        // 先与反方向相互抵消, 一进一出的那部分对整个 Inventory 没有净影响.
        for (int i = 0; i < oppositeDirection.size() && remaining > 0; ) {
            NetItem opposite = oppositeDirection.get(i);
            if (!ItemUtils.isSimilar(template, opposite.template())) {
                i++;
                continue;
            }
            int matched = Math.min(remaining, opposite.amount());
            remaining -= matched;
            // 整条被抵消干净时移除它, 后面的元素会往前顶, 因此这一轮不能推进下标.
            if (matched == opposite.amount()) {
                oppositeDirection.remove(i);
            } else {
                oppositeDirection.set(i, new NetItem(opposite.template(), opposite.amount() - matched));
                i++;
            }
        }
        // 复用同方向记录的剩余堆叠空间.
        for (int i = 0; i < sameDirection.size() && remaining > 0; i++) {
            NetItem same = sameDirection.get(i);
            if (!ItemUtils.isSimilar(template, same.template())) {
                continue;
            }
            int accepted = Math.min(remaining, same.template().getMaxStackSize() - same.amount());
            if (accepted > 0) {
                sameDirection.set(i, new NetItem(same.template(), same.amount() + accepted));
                remaining -= accepted;
            }
        }
        // 新条目按物品上限分堆.
        while (remaining > 0) {
            int stackAmount = Math.min(remaining, template.getMaxStackSize());
            sameDirection.add(new NetItem(template, stackAmount));
            remaining -= stackAmount;
        }
    }

    // 按累计结果生成独立的物品副本, 订阅者拿到后怎么改都不会影响事件内部记录.
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
