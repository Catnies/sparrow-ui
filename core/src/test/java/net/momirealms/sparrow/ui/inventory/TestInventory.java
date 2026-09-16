package net.momirealms.sparrow.ui.inventory;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class TestInventory extends SparrowInventory {

    public TestInventory(int size) {
        super(new ItemStack[size]);
    }

    public TestInventory(@Nullable ItemStack @NotNull [] initial) {
        super(initial);
    }
}
