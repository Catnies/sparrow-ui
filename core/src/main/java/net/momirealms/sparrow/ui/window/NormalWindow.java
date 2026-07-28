package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface NormalWindow extends Window {

    /**
     * 创建默认使用 9x6 上部 GUI 的 Builder.
     *
     * @return 普通窗口 Builder
     */
    static @NotNull Builder builder() {
        return new NormalWindowImpl.BuilderImpl();
    }

    /**
     * 创建单个 GUI 同时覆盖容器与玩家物品栏区域的 Builder.
     *
     * @param gui 宽 9、高 5 至 10 的合并 GUI
     * @return 普通窗口 Builder
     */
    static @NotNull Builder mergedBuilder(@NotNull Gui gui) {
        return new NormalWindowImpl.BuilderImpl(gui);
    }

    /**
     * 普通箱子 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<NormalWindow, Builder> {

        /**
         * 设置 9 列、1 至 6 行的上部 GUI.
         *
         * @param upperGui 上部 GUI
         * @return 此 Builder
         */
        @NotNull Builder setUpperGui(@NotNull Gui upperGui);

        /**
         * 设置控制玩家物品栏区域的 9x4 GUI; null 表示映射玩家真实物品栏.
         *
         * @param lowerGui 下部 GUI
         * @return 此 Builder
         */
        @NotNull Builder setLowerGui(@Nullable Gui lowerGui);

        @Override
        @NotNull Builder clone();
    }
}
