package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface StonecutterWindow extends Window {

    /**
     * 创建使用 2x1 上部 GUI 和空 4x0 按钮 GUI 的 Builder.
     *
     * @return 切石机 Window Builder
     */
    @NotNull
    static Builder builder() {
        return new StonecutterWindowImpl.BuilderImpl();
    }

    /**
     * 返回当前选中的配方按钮索引.
     *
     * @return 配方按钮索引, -1 表示未选择
     */
    int getSelectedRecipeIndex();

    /**
     * 设置当前选中的配方按钮.
     * <p>-1 表示清除选择. Window 打开时, 非负索引必须属于当前发送给客户端的有效
     * 按钮前缀; Window 未打开时只按按钮 GUI 的容量校验, 并在下次初始渲染时把越界选择校正为 -1.
     *
     * @param index 配方按钮索引或 -1
     */
    void setSelectedRecipeIndex(int index);

    /**
     * 切石机 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<StonecutterWindow, Builder> {

        /**
         * 设置必须为 2x1 的上部 GUI.
         *
         * @param upperGui 输入与结果 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setUpperGui(@NotNull Gui upperGui);

        /**
         * 设置控制玩家物品栏区域的 9x4 GUI; null 表示连接玩家 Bukkit Inventory.
         *
         * @param lowerGui 下部 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerGui(@Nullable Gui lowerGui);

        /**
         * 设置固定宽度为 4 的配方按钮 GUI.
         * <p>Builder 默认使用 4x0 空 GUI. Window 构建后不允许替换此 GUI;
         * 调用方可以继续直接修改同一个 GUI 的内容.
         *
         * @param buttonsGui 配方按钮 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setButtonsGui(@NotNull Gui buttonsGui);

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
