package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Consumer;

public interface AnvilWindow extends Window {

    /**
     * 客户端最近一次提交上来的重命名文本.
     *
     * @return 重命名文本
     */
    @NotNull String getRenameText();

    /**
     * 当前显示的等级消耗.
     *
     * @return 等级消耗
     */
    int getEnchantmentCost();

    /**
     * 设置客户端铁砧界面上显示的等级消耗.
     *
     * @param enchantmentCost 等级消耗
     */
    void setEnchantmentCost(int enchantmentCost);

    /**
     * 输入槽空着的时候, 文本框是不是还开着.
     *
     * @return 一直开着时为 true
     */
    boolean isTextFieldAlwaysEnabled();

    /**
     * 设置输入槽空着时, 是不是拿一件看不见的占位物把文本框撑成可编辑.
     * <p>开了之后那件占位物会带着客户端最近提交的重命名文本, 于是纠正客户端对空输入槽的点击预测时,
     * 文本框不会被空名字顶掉.
     *
     * @param textFieldAlwaysEnabled 是否始终启用文本框
     */
    void setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled);

    /**
     * 结果槽空着的时候, 结果按钮是不是照样有效.
     *
     * @return 一直有效时为 true
     */
    boolean isResultAlwaysValid();

    /**
     * 设置结果槽空着时, 是不是拿看不见的占位物把结果按钮撑成有效.
     *
     * @param resultAlwaysValid 是否始终保持结果有效
     */
    void setResultAlwaysValid(boolean resultAlwaysValid);

    /**
     * 整批换掉重命名文本变化的处理器.
     *
     * @param handlers 新处理器列表
     */
    void setRenameHandlers(@NotNull List<? extends Consumer<? super String>> handlers);

    /**
     * 现在的重命名处理器, 给一份快照.
     *
     * @return 不可修改的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<String>> getRenameHandlers();

    /**
     * 追加一个重命名文本变化处理器.
     *
     * @param handler 重命名处理器
     */
    void addRenameHandler(@NotNull Consumer<? super String> handler);

    /**
     * 按 equals 摘掉一个重命名处理器.
     *
     * @param handler 要移除的处理器
     */
    void removeRenameHandler(@NotNull Consumer<? super String> handler);

    static @NotNull Builder builder() {
        return new AnvilWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<AnvilWindow, Builder> {

        /**
         * 设置上部 Pane, 尺寸必须是 3x1.
         *
         * @param upperPane 上部 Pane
         * @return 此 Builder
         */
        @NotNull Builder setUpperPane(@NotNull Pane upperPane);

        /**
         * 设置下部那个 9x4 的 Pane, 它管玩家物品栏那一片; 给 null 就接玩家的 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull Builder setLowerPane(@Nullable Pane lowerPane);

        /**
         * 设置一开始显示的等级消耗.
         *
         * @param enchantmentCost 等级消耗
         * @return 此 Builder
         */
        @NotNull Builder setEnchantmentCost(int enchantmentCost);

        /**
         * 设置输入槽空着时是否还用占位撑住文本框, 语义同 {@link AnvilWindow#setTextFieldAlwaysEnabled(boolean)}.
         *
         * @param textFieldAlwaysEnabled 是否始终启用文本框
         * @return 此 Builder
         */
        @NotNull Builder setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled);

        /**
         * 设置结果槽空着时是否还让结果按钮有效.
         *
         * @param resultAlwaysValid 是否始终保持结果有效
         * @return 此 Builder
         */
        @NotNull Builder setResultAlwaysValid(boolean resultAlwaysValid);

        /**
         * 整批换掉重命名文本变化的处理器.
         *
         * @param handlers 新处理器列表
         * @return 此 Builder
         */
        @NotNull Builder setRenameHandlers(@NotNull List<? extends Consumer<? super String>> handlers);

        /**
         * 追加一个重命名文本变化处理器.
         *
         * @param handler 重命名处理器
         * @return 此 Builder
         */
        @NotNull Builder addRenameHandler(@NotNull Consumer<? super String> handler);

        @Override
        @NotNull Builder clone();
    }
}
