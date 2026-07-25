package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家从原版配方书中选择已解锁配方时产生的事件.
 * <p>此事件只报告选择意图, 不会移动材料或修改菜单输入槽和结果槽.
 *
 * @param player 选择配方的玩家
 * @param window 所属配方书 Window
 * @param recipeId 配方资源标识符
 * @param makeAll 客户端是否请求尽可能多地制作
 */
public record RecipeBookSelect(
        @NotNull Player player,
        @NotNull RecipeBookWindow window,
        @NotNull Key recipeId,
        boolean makeAll
) {
}
