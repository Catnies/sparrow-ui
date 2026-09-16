package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一次放入判断的上下文, 玩家交互带来的放入能看到发起者与所在的 Window.
 * <p><strong>{@link #item()} 是内部只读引用, 不得修改或持有</strong>.
 *
 * @param item 要放入的物品
 * @param player 发起放入的玩家, 程序写入时为 {@code null}
 * @param window 放入发生的 Window, 程序写入时为 {@code null}
 */
public record PlacementContext(
        @NotNull ItemStack item,
        @Nullable Player player,
        @Nullable Window window
) {
}
