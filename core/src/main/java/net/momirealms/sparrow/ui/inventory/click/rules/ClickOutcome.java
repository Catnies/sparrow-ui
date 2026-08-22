package net.momirealms.sparrow.ui.inventory.click.rules;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 一次单槽点击算完之后, 这一格和光标各自该变成什么样.
// placementInput 是真正落进槽位的那件东西.
// 从收纳袋里掏物品时, 光标拿着袋子, 进槽的却是袋子里的某一件, 槽级放入规则要检查的是后者.
@ApiStatus.Internal
public record ClickOutcome(
        @Nullable ItemStack slotAfter,
        @NotNull ItemStack cursorAfter,
        @Nullable ItemStack placementInput
) {

    // 放入物就是光标本身的常规结果, 由调用方自己从光标取值.
    ClickOutcome(@Nullable ItemStack slotAfter, @NotNull ItemStack cursorAfter) {
        this(slotAfter, cursorAfter, null);
    }
}
