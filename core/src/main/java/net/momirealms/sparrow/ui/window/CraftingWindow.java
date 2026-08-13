package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface CraftingWindow extends RecipeBookWindow {

    /**
     * 创建使用 3x3 合成 Pane 和 1x1 结果 Pane 的 Builder.
     *
     * @return 工作台 Window Builder
     */
    @NotNull
    static Builder builder() {
        return new CraftingWindowImpl.BuilderImpl();
    }

    /**
     * 工作台 Window 的可重复 Builder.
     */
    interface Builder extends RecipeBookWindow.Builder<CraftingWindow, Builder> {

        /**
         * 设置必须为 3x3 的合成网格 Pane.
         *
         * @param craftingPane 合成网格 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setCraftingPane(@NotNull Pane craftingPane);

        /**
         * 设置必须为 1x1 的结果 Pane.
         *
         * @param resultPane 结果 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setResultPane(@NotNull Pane resultPane);

        /**
         * 设置控制玩家物品栏区域的 9x4 Pane; null 表示连接玩家 Bukkit Inventory.
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
