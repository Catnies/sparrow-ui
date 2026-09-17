package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface StonecutterWindow extends Window {

    /**
     * 当前选中的是哪个配方按钮.
     *
     * @return 配方按钮索引; -1 表示没选
     */
    int getSelectedRecipeIndex();

    /**
     * 设置当前选中的配方按钮.
     * <p>-1 就是清掉选择. Window 开着的时候, 非负索引必须落在当前真的发给客户端的那个按钮前缀里;
     * 还没打开时只按按钮 Pane 的容量校验, 越界的选择留到下次初始渲染时校正成 -1.
     *
     * @param index 配方按钮索引或 -1
     * @throws IndexOutOfBoundsException 索引超出按钮 Pane 容量时
     */
    void setSelectedRecipeIndex(int index);

    @NotNull
    static Builder builder() {
        return new StonecutterWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<StonecutterWindow, Builder> {

        /**
         * 设置上部 Pane, 输入和结果都在这一块上, 尺寸必须是 2x1.
         *
         * @param upperPane 输入与结果 Pane
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

        /**
         * 设置配方按钮 Pane, 宽度固定为 4.
         * <p>Builder 默认给一块 4x0 的空 Pane. Window 建好之后不能再换这块 Pane,
         * 调用方想改内容就接着改同一个 Pane.
         *
         * @param buttonsPane 配方按钮 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setButtonsPane(@NotNull Pane buttonsPane);

        /**
         * 设置一开始选中的那个按钮.
         *
         * @param index 配方按钮索引或 -1
         * @return 此 Builder
         */
        @NotNull
        Builder setSelectedRecipeIndex(int index);

        @Override
        @NotNull
        Builder clone();
    }
}
