package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 配方书菜单共用的 recipe display 解析与 ghost recipe 能力.
 */
@ApiStatus.Internal
public interface RecipeBookMenuHandle extends MenuHandle {

    /**
     * 把客户端 recipe display id 解析为配方资源标识符.
     *
     * @param displayId 客户端 display id
     * @return 对应配方标识符, 未知时为 {@code null}
     */
    @Nullable
    Key recipeKey(int displayId);

    /**
     * 发送指定配方的首个 recipe display.
     *
     * @param recipeId 配方资源标识符
     * @return 找到并发送 display 时为 {@code true}
     */
    boolean sendGhostRecipe(@NotNull Key recipeId);
}
