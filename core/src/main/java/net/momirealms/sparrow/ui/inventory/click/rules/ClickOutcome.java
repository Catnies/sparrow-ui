package net.momirealms.sparrow.ui.inventory.click.rules;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// placementInput 记录 Bundle 取出路径中真正进入槽位的物品.
@ApiStatus.Internal
public record ClickOutcome(
        @Nullable ItemStack slotAfter,
        @NotNull ItemStack cursorAfter,
        @Nullable ItemStack placementInput
) {

    ClickOutcome(@Nullable ItemStack slotAfter, @NotNull ItemStack cursorAfter) {
        this(slotAfter, cursorAfter, null);
    }
}
