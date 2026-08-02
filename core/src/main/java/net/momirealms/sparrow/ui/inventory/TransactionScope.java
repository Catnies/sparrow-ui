package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.RootInventoryChange;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 记录一笔事务准备怎样修改一个 RootInventory.
 * <p>{@code change} 说明哪些槽位要从什么物品变成什么物品. {@code planned} 是计算这些变化时,
 * RootInventory 当时正在使用的内容数组, 它不是后来重新复制的一份快照. 真正提交前会检查 RootInventory
 * 是否仍然使用同一个数组: 如果已经换成其他数组, 说明期间有另一笔事务先提交了, 当前事务必须放弃,
 * 以免用过期结果覆盖新内容.
 *
 * @param change 这个 RootInventory 准备提交的槽位变化
 * @param planned 计算这些变化时 RootInventory 使用的内容数组
 */
record TransactionScope(@NotNull RootInventoryChange change, @Nullable ItemStack @NotNull [] planned) {

    /**
     * 根据 RootInventory 和槽位变化创建一组待提交内容.
     *
     * @param inventory 要修改的 RootInventory
     * @param planned 计算槽位变化时该 RootInventory 使用的内容数组
     * @param slotChanges 准备写入的槽位及其前后值
     */
    TransactionScope(@NotNull RootInventory inventory, @Nullable ItemStack @NotNull [] planned, @NotNull List<SlotChange> slotChanges) {
        this(new RootInventoryChange(inventory, slotChanges), planned);
    }

    // 返回这组修改所属的 RootInventory.
    @NotNull
    RootInventory inventory() {
        return this.change.inventory();
    }

    // 返回准备写入该 RootInventory 的槽位变化.
    @NotNull
    List<SlotChange> slotChanges() {
        return this.change.slotChanges();
    }
}
