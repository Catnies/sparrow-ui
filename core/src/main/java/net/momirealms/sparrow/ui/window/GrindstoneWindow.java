package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface GrindstoneWindow extends Window {

    /**
     * 创建默认使用 1x2 输入 Pane 和 1x1 结果 Pane 的 Builder.
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
         * 设置必须为 1x2 的输入 Pane.
         *
         * @param inputPane 输入 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setInputPane(@NotNull Pane inputPane);

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
