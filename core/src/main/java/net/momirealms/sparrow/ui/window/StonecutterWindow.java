package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface StonecutterWindow extends Window {

    /**
     * 返回当前选中的配方按钮索引.
     *
     * @return 配方按钮索引, -1 表示未选择
     */
    int getSelectedRecipeIndex();

    /**
     * 设置当前选中的配方按钮.
     * <p>-1 表示清除选择. Window 打开时, 非负索引必须属于当前发送给客户端的有效
     * 按钮前缀; Window 未打开时只按按钮 Pane 的容量校验, 并在下次初始渲染时把越界选择校正为 -1.
     *
     * @param index 配方按钮索引或 -1
     */
    void setSelectedRecipeIndex(int index);

    @NotNull
    static Builder builder() {
        return new StonecutterWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<StonecutterWindow, Builder> {

        /**
         * 设置必须为 2x1 的上部 Pane.
         *
         * @param upperPane 输入与结果 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setUpperPane(@NotNull Pane upperPane);

        /**
         * 设置控制玩家物品栏区域的 9x4 Pane; null 表示连接玩家 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerPane(@Nullable Pane lowerPane);

        /**
         * 设置固定宽度为 4 的配方按钮 Pane.
         * <p>Builder 默认使用 4x0 空 Pane. Window 构建后不允许替换此 Pane;
         * 调用方可以继续直接修改同一个 Pane 的内容.
         *
         * @param buttonsPane 配方按钮 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setButtonsPane(@NotNull Pane buttonsPane);

        /**
         * 设置初始选择.
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
