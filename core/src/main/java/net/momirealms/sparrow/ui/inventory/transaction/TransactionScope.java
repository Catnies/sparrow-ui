package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.InventoryChange;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@ApiStatus.Internal
public record TransactionScope(@NotNull InventoryChange change, @NotNull PlannedRoot basis) {

    public TransactionScope(@NotNull PlannedRoot basis, @NotNull List<SlotChange> slotChanges) {
        this(new InventoryChange(basis.inventory(), slotChanges), basis);
    }

    @NotNull
    public SparrowInventory inventory() {
        return this.change.inventory();
    }

    @NotNull
    public List<SlotChange> slotChanges() {
        return this.change.slotChanges();
    }

    // <strong>规划内容只读</strong>, 是否失效由 basis 判断.
    public @Nullable ItemStack @NotNull [] planned() {
        return this.basis.planned();
    }

    // Pre 编辑只替换变更, 保留原规划基准.
    @NotNull
    public TransactionScope withSlotChanges(@NotNull List<SlotChange> slotChanges) {
        return new TransactionScope(this.basis, slotChanges);
    }
}
