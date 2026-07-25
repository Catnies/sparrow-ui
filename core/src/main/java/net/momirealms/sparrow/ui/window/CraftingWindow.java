package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 使用原版工作台界面的四十六槽 Window.
 *
 * <p>raw slot {@code 0} 是结果, {@code 1..9} 是 3x3 合成网格,
 * {@code 10..45} 是玩家物品栏区域. 原版配方书只报告选择并显示 ghost recipe,
 * 不会自动搬运材料.
 */
public interface CraftingWindow extends RecipeBookWindow {

    /**
     * 创建使用 3x3 合成 GUI 和 1x1 结果 GUI 的 Builder.
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
         * 设置必须为 3x3 的合成网格 GUI.
         *
         * @param craftingGui 合成网格 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setCraftingGui(@NotNull Gui craftingGui);

        /**
         * 设置必须为 1x1 的结果 GUI.
         *
         * @param resultGui 结果 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setResultGui(@NotNull Gui resultGui);

        /**
         * 设置控制玩家物品栏区域的 9x4 GUI; null 表示映射玩家真实物品栏.
         *
         * @param lowerGui 下部 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerGui(@Nullable Gui lowerGui);

        @Override
        @NotNull
        Builder clone();
    }
}
