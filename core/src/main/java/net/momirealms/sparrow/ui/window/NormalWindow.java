package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface NormalWindow extends Window {

    /**
     * 创建默认使用 9x6 上部 Pane 的 Builder.
     *
     * @return 普通窗口 Builder
     */
    static @NotNull Builder builder() {
        return new NormalWindowImpl.BuilderImpl();
    }

    /**
     * 创建单个 Pane 同时覆盖容器与玩家物品栏区域的 Builder.
     *
     * @param pane 宽 9, 高 5 至 10 的合并 Pane
     * @return 普通窗口 Builder
     */
    static @NotNull Builder mergedBuilder(@NotNull Pane pane) {
        return new NormalWindowImpl.BuilderImpl(pane);
    }

    /**
     * 普通箱子 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<NormalWindow, Builder> {

        /**
         * 设置 9 列, 1 至 6 行的上部 Pane.
         *
         * @param upperPane 上部 Pane
         * @return 此 Builder
         */
        @NotNull Builder setUpperPane(@NotNull Pane upperPane);

        /**
         * 设置控制玩家物品栏区域的 9x4 Pane; null 表示连接玩家 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull Builder setLowerPane(@Nullable Pane lowerPane);

        @Override
        @NotNull Builder clone();
    }
}
