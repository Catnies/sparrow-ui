package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 使用原版砂轮界面的三槽 Window.
 */
public interface GrindstoneWindow extends Window {

    /**
     * 创建默认使用 1x2 输入 GUI 和 1x1 结果 GUI 的 Builder.
     *
     * @return 砂轮窗口 Builder
     */
    @NotNull
    static Builder builder() {
        return new GrindstoneWindowImpl.BuilderImpl();
    }

    /**
     * 砂轮 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<GrindstoneWindow, Builder> {

        /**
         * 设置必须为 1x2 的输入 GUI.
         *
         * @param inputGui 输入 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setInputGui(@NotNull Gui inputGui);

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
