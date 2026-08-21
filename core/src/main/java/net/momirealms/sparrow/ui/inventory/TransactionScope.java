package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.InventoryChange;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

// 记录一笔事务准备怎样修改一个 Inventory.
@ApiStatus.Internal
public record TransactionScope(@NotNull InventoryChange change, @NotNull PlannedRoot basis) {

    // 把一次规划算出的槽位变化整理成待提交内容.
    TransactionScope(@NotNull PlannedRoot basis, @NotNull List<SlotChange> slotChanges) {
        this(new InventoryChange(basis.inventory(), slotChanges), basis);
    }

    // 供 Pre 阶段改写已经参与事务的 Inventory: planned 会被重新包一层, 而这种基准的校验依据本来就是数组引用本身, 包装不影响结果.
    public TransactionScope(@NotNull SparrowInventory inventory, @Nullable ItemStack @NotNull [] planned, @NotNull List<SlotChange> slotChanges) {
        this(new InventoryChange(inventory, slotChanges), new PlannedRoot.Stm(inventory, planned));
    }

    @NotNull
    public SparrowInventory inventory() {
        return this.change.inventory();
    }

    @NotNull
    public List<SlotChange> slotChanges() {
        return this.change.slotChanges();
    }

    // 规划期看到的内容, 只供读取, 不承担校验职责.
    public @Nullable ItemStack @NotNull [] planned() {
        return this.basis.planned();
    }

    // 换掉槽位变化但留住原基准, 给 Pre 编辑和记账口径修正用: 改写永远不换校验依据.
    @NotNull
    public TransactionScope withSlotChanges(@NotNull List<SlotChange> slotChanges) {
        return new TransactionScope(this.basis, slotChanges);
    }
}
