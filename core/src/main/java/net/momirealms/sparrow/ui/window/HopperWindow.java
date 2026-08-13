package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface HopperWindow extends Window {

    /**
     * 创建默认使用 5x1 上部 Pane 的 Builder.
     *
     * @return 漏斗窗口 Builder
     */
    static @NotNull Builder builder() {
        return new HopperWindowImpl.BuilderImpl();
    }

    /**
     * 漏斗 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<HopperWindow, Builder> {

        /**
         * 设置必须为 5x1 的上部 Pane.
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
