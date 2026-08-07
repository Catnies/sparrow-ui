package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.InventoryChange;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 记录一笔事务准备怎样修改一个 Inventory.
 * <p>{@code change} 说明哪些槽位要从什么物品变成什么物品. {@code basis} 是计算这些变化时读到的
 * 规划基准: 提交前引擎凭它确认规划依据仍然有效, 期间有另一笔事务先提交时当前事务必须放弃,
 * 以免用过期结果覆盖新内容. {@link #planned()} 是该基准的内容视图, 只用于读取规划期内容,
 * 不承担任何校验职责.
 *
 * @param change 这个 Inventory 准备提交的槽位变化
 * @param basis 计算这些变化时读到的规划基准
 */
@ApiStatus.Internal
public record TransactionScope(@NotNull InventoryChange change, @NotNull SparrowInventory.PlannedRoot basis) {

    /**
     * 把一次规划算出的槽位变化整理成待提交内容.
     *
     * @param basis 本次规划读到的状态版本
     * @param slotChanges 规划算出的槽位变化, 不能为空
     */
    TransactionScope(@NotNull SparrowInventory.PlannedRoot basis, @NotNull List<SlotChange> slotChanges) {
        this(new InventoryChange(basis.inventory(), slotChanges), basis);
    }

    /**
     * 根据 Inventory 和内容数组创建一组待提交内容, 供 Pre 阶段改写已经参与事务的 Inventory.
     * <p>数组会被包装成状态自有家族的基准: 该家族的校验 token 就是数组引用本身, 重新包装不改变校验结果.
     *
     * @param inventory 要修改的 Inventory
     * @param planned 计算槽位变化时该 Inventory 使用的内容数组
     * @param slotChanges 准备写入的槽位及其前后值
     */
    public TransactionScope(@NotNull SparrowInventory inventory, @Nullable ItemStack @NotNull [] planned, @NotNull List<SlotChange> slotChanges) {
        this(new InventoryChange(inventory, slotChanges), new SparrowInventory.PlannedRoot.Stm(inventory, planned));
    }

    // 返回这组修改所属的 Inventory.
    @NotNull
    public SparrowInventory inventory() {
        return this.change.inventory();
    }

    // 返回准备写入该 Inventory 的槽位变化.
    @NotNull
    public List<SlotChange> slotChanges() {
        return this.change.slotChanges();
    }

    // 返回规划基准的内容视图.
    public @Nullable ItemStack @NotNull [] planned() {
        return this.basis.planned();
    }

    /**
     * 在同一个规划基准上替换槽位变化, 供 Pre 编辑与记账口径修正使用: 改写永不更换校验 token.
     *
     * @param slotChanges 新的槽位变化
     * @return 持有同一基准与新变化的待提交内容
     */
    @NotNull
    public TransactionScope withSlotChanges(@NotNull List<SlotChange> slotChanges) {
        return new TransactionScope(this.basis, slotChanges);
    }
}
