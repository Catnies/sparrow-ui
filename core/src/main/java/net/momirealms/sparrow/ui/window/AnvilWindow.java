package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Consumer;

public interface AnvilWindow extends Window {

    /**
     * 创建默认使用 3x1 上部 Pane 的 Builder.
     *
     * @return 铁砧窗口 Builder
     */
    static @NotNull Builder builder() {
        return new AnvilWindowImpl.BuilderImpl();
    }

    /**
     * 返回最近一次由客户端提交的重命名文本.
     *
     * @return 重命名文本
     */
    @NotNull String getRenameText();

    /**
     * 返回当前等级消耗.
     *
     * @return 等级消耗
     */
    int getEnchantmentCost();

    /**
     * 设置客户端铁砧界面显示的等级消耗.
     *
     * @param enchantmentCost 等级消耗
     */
    void setEnchantmentCost(int enchantmentCost);

    /**
     * 返回输入槽为空时是否仍启用文本框.
     *
     * @return 是否始终启用文本框
     */
    boolean isTextFieldAlwaysEnabled();

    /**
     * 设置输入槽为空时是否以不可见占位物保持文本框可编辑.
     * <p>启用后, 占位物会携带最近一次由客户端提交的重命名文本;
     * 纠正客户端对空输入槽的点击预测时, 文本框不会被空名称重置.
     *
     * @param textFieldAlwaysEnabled 是否始终启用文本框
     */
    void setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled);

    /**
     * 返回结果槽为空时是否仍保持结果按钮有效.
     *
     * @return 是否始终保持结果有效
     */
    boolean isResultAlwaysValid();

    /**
     * 设置结果槽为空时是否以不可见占位物保持结果按钮有效.
     *
     * @param resultAlwaysValid 是否始终保持结果有效
     */
    void setResultAlwaysValid(boolean resultAlwaysValid);

    /**
     * 替换重命名文本变化处理器列表.
     *
     * @param handlers 新处理器列表
     */
    void setRenameHandlers(@NotNull List<? extends Consumer<? super String>> handlers);

    /**
     * 返回重命名文本变化处理器快照.
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
     * 移除一个与给定对象相等的重命名处理器.
     *
     * @param handler 要移除的处理器
     */
    void removeRenameHandler(@NotNull Consumer<? super String> handler);

    /**
     * 铁砧 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<AnvilWindow, Builder> {

        /**
         * 设置必须为 3x1 的上部 Pane.
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

        /**
         * 设置初始等级消耗.
         *
         * @param enchantmentCost 等级消耗
         * @return 此 Builder
         */
        @NotNull Builder setEnchantmentCost(int enchantmentCost);

        /**
         * 设置输入槽为空时是否仍启用文本框.
         * 语义同 {@link AnvilWindow#setTextFieldAlwaysEnabled(boolean)}.
         *
         * @param textFieldAlwaysEnabled 是否始终启用文本框
         * @return 此 Builder
         */
        @NotNull Builder setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled);

        /**
         * 设置结果槽为空时是否仍保持结果按钮有效.
         *
         * @param resultAlwaysValid 是否始终保持结果有效
         * @return 此 Builder
         */
        @NotNull Builder setResultAlwaysValid(boolean resultAlwaysValid);

        /**
         * 替换重命名文本变化处理器列表.
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
