package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface SmithingWindow extends Window {

    /**
     * 创建默认使用 4x1 上部 GUI 的 Builder.
     *
     * @return 锻造台窗口 Builder
     */
    @NotNull
    static Builder builder() {
        return new SmithingWindowImpl.BuilderImpl();
    }

    /**
     * 锻造台 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<SmithingWindow, Builder> {

        /**
         * 设置必须为 4x1 的上部 GUI.
         *
         * @param upperGui 上部 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setUpperGui(@NotNull Gui upperGui);

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
