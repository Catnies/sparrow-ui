package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface CraftingWindow extends RecipeBookWindow {

    @NotNull
    static Builder builder() {
        return new CraftingWindowImpl.BuilderImpl();
    }

    interface Builder extends RecipeBookWindow.Builder<CraftingWindow, Builder> {

        /**
         * 设置合成网格 Pane, 尺寸必须是 3x3.
         *
         * @param craftingPane 合成网格 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setCraftingPane(@NotNull Pane craftingPane);

        /**
         * 设置结果 Pane, 尺寸必须是 1x1.
         *
         * @param resultPane 结果 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setResultPane(@NotNull Pane resultPane);

        /**
         * 设置下部那个 9x4 的 Pane, 管玩家物品栏那一片; 给 null 就接玩家的 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerPane(@Nullable Pane lowerPane);

        @Override
        @NotNull
        Builder clone();
    }
}
