package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface DropperWindow extends Window {

    @NotNull
    static Builder builder() {
        return new DropperWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<DropperWindow, Builder> {

        /**
         * 设置上部 Pane, 尺寸必须是 3x3.
         *
         * @param upperPane 上部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setUpperPane(@NotNull Pane upperPane);

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
