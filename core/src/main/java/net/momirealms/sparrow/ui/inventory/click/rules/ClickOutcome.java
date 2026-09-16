package net.momirealms.sparrow.ui.inventory.click.rules;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 收纳袋语义显式记录袋内物品流动, 普通点击由槽位前后内容推导.
@ApiStatus.Internal
public record ClickOutcome(
        @Nullable ItemStack slotAfter,
        @NotNull ItemStack cursorAfter,
        @Nullable ItemStack addedItem,
        @Nullable ItemStack removedItem
) {

    ClickOutcome(@Nullable ItemStack slotAfter, @NotNull ItemStack cursorAfter) {
        this(slotAfter, cursorAfter, null, null);
    }
}
