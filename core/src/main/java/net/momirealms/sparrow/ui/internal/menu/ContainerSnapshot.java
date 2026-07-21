package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.util.ItemSnapshots;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Window 菜单一次完整的物品与光标快照.
 */
@ApiStatus.Internal
public final class ContainerSnapshot {
    private final List<ItemStack> slots;
    private final ItemStack cursor;
    private final ContainerRevision revision;

    /**
     * 创建与调用方物品对象隔离的容器快照.
     *
     * @param slots 按原始槽位编号排列的物品
     * @param cursor 光标物品
     * @param revision 此快照对应的协议版本
     */
    public ContainerSnapshot(
            @NotNull List<? extends ItemStack> slots,
            @NotNull ItemStack cursor,
            @NotNull ContainerRevision revision
    ) {
        ArrayList<ItemStack> copies = new ArrayList<>(slots.size());
        for (int index = 0; index < slots.size(); index++) {
            copies.add(ItemSnapshots.copyOrEmpty(slots.get(index)));
        }
        this.slots = List.copyOf(copies);
        this.cursor = ItemSnapshots.copyOrEmpty(cursor);
        this.revision = revision;
    }

    public int size() {
        return this.slots.size();
    }

    /**
     * 返回指定槽位的副本.
     *
     * @param slot 原始槽位编号
     * @return 与快照隔离的物品副本
     */
    public @NotNull ItemStack item(int slot) {
        return this.slots.get(slot).clone();
    }

    /**
     * 返回全部槽位物品的不可变副本列表.
     *
     * @return 槽位物品副本
     */
    public @NotNull List<ItemStack> items() {
        ArrayList<ItemStack> copies = new ArrayList<>(this.slots.size());
        for (int index = 0; index < this.slots.size(); index++) {
            copies.add(this.slots.get(index).clone());
        }
        return List.copyOf(copies);
    }

    /**
     * 返回光标物品的副本.
     *
     * @return 与快照隔离的光标物品
     */
    public @NotNull ItemStack carried() {
        return this.cursor.clone();
    }

    public @NotNull ContainerRevision revision() {
        return this.revision;
    }

    @NotNull ItemStack itemView(int slot) {
        return this.slots.get(slot);
    }

    @NotNull ItemStack carriedView() {
        return this.cursor;
    }
}
