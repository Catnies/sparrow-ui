package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface GrindstoneWindow extends Window {

    @NotNull
    static Builder builder() {
        return new GrindstoneWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<GrindstoneWindow, Builder> {

        /**
         * 设置输入 Pane, 尺寸必须是 1x2.
         *
         * @param inputPane 输入 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setInputPane(@NotNull Pane inputPane);

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
