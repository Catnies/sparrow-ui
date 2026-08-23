package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface DispenserWindow extends Window {

    @NotNull
    static Builder builder() {
        return new DispenserWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<DispenserWindow, Builder> {

        /**
         * 设置必须为 3x3 的上部 Pane.
         *
         * @param upperPane 上部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setUpperPane(@NotNull Pane upperPane);

        /**
         * 设置控制玩家物品栏区域的 9x4 Pane, null 表示连接玩家 Bukkit Inventory.
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
