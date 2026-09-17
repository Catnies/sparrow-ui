package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface NormalWindow extends Window {

    /**
     * 建一个 Builder, 上部 Pane 默认按 9x6 算.
     *
     * @return 普通窗口 Builder
     */
    static @NotNull Builder builder() {
        return new NormalWindowImpl.BuilderImpl();
    }

    /**
     * 建一个合并窗口的 Builder: 一个 Pane 同时盖住容器和玩家物品栏.
     *
     * @param pane 宽 9, 高 5 至 10 的合并 Pane
     * @return 普通窗口 Builder
     */
    static @NotNull Builder mergedBuilder(@NotNull Pane pane) {
        return new NormalWindowImpl.BuilderImpl(pane);
    }

    /**
     * 普通箱子 Window 的 Builder.
     */
    interface Builder extends Window.Builder<NormalWindow, Builder> {

        /**
         * 设置上部 Pane, 9 列, 1 到 6 行.
         *
         * @param upperPane 上部 Pane
         * @return 此 Builder
         */
        @NotNull Builder setUpperPane(@NotNull Pane upperPane);

        /**
         * 设置下部那个 9x4 的 Pane, 管玩家物品栏那一片; 给 null 就接玩家的 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull Builder setLowerPane(@Nullable Pane lowerPane);

        @Override
        @NotNull Builder clone();
    }
}
