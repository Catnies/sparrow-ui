package net.momirealms.sparrow.ui.window.click;

import net.kyori.adventure.key.Key;
import net.momirealms.sparrow.ui.window.RecipeBookWindow;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家在原版配方书里点了某个已解锁配方时的事件.
 *
 * @param player 选择配方的玩家
 * @param window 配方所属的那扇窗
 * @param recipeId 配方资源标识符
 * @param makeAll 客户端有没有要求尽可能多地做
 */
public record RecipeBookSelectClick(
        @NotNull Player player,
        @NotNull RecipeBookWindow window,
        @NotNull Key recipeId,
        boolean makeAll
) {
}
