package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.BiConsumer;

public interface CrafterWindow extends Window {

    /**
     * 创建使用 3x3 输入 Pane 和 1x1 结果 Pane 的 Builder.
     *
     * @return 合成器窗口 Builder
     */
    @NotNull
    static Builder builder() {
        return new CrafterWindowImpl.BuilderImpl();
    }

    /**
     * 设置输入槽的禁用状态.
     *
     * @param slot 输入槽编号
     * @param disabled true 表示禁用
     */
    void setSlotDisabled(int slot, boolean disabled);

    /**
     * 返回输入槽是否已禁用.
     *
     * @param slot 输入槽编号
     * @return 禁用时为 true
     */
    boolean isSlotDisabled(int slot);

    /**
     * 替换玩家切换输入槽状态时调用的处理器.
     *
     * @param handlers 接收槽位编号和新禁用状态的处理器
     */
    void setSlotToggleHandlers(
            @NotNull List<? extends BiConsumer<? super Integer, ? super Boolean>> handlers
    );

    /**
     * 返回输入槽状态处理器快照.
     *
     * @return 不可修改的处理器列表
     */
    @Unmodifiable
    @NotNull
    List<BiConsumer<Integer, Boolean>> getSlotToggleHandlers();

    /**
     * 添加输入槽状态处理器.
     *
     * @param handler 接收槽位编号和新禁用状态的处理器
     */
    void addSlotToggleHandler(@NotNull BiConsumer<? super Integer, ? super Boolean> handler);

    /**
     * 移除输入槽状态处理器.
     *
     * @param handler 要移除的处理器
     */
    void removeSlotToggleHandler(@NotNull BiConsumer<? super Integer, ? super Boolean> handler);

    /**
     * 合成器 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<CrafterWindow, Builder> {

        /**
         * 设置必须为 3x3 的输入 Pane.
         *
         * @param craftingPane 合成输入 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setCraftingPane(@NotNull Pane craftingPane);

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

        /**
         * 设置初始输入槽禁用状态.
         *
         * @param slot 输入槽编号
         * @param disabled true 表示禁用
         * @return 此 Builder
         */
        @NotNull
        Builder setSlotDisabled(int slot, boolean disabled);

        /**
         * 一次设置全部九个输入槽的初始禁用状态.
         *
         * @param disabledSlots 按槽位编号排列的九个状态
         * @return 此 Builder
         */
        @NotNull
        Builder setDisabledSlots(boolean @NotNull ... disabledSlots);

        /**
         * 替换玩家输入槽状态处理器.
         *
         * @param handlers 接收槽位编号和新禁用状态的处理器
         * @return 此 Builder
         */
        @NotNull
        Builder setSlotToggleHandlers(
                @NotNull List<? extends BiConsumer<? super Integer, ? super Boolean>> handlers
        );

        /**
         * 添加玩家输入槽状态处理器.
         *
         * @param handler 接收槽位编号和新禁用状态的处理器
         * @return 此 Builder
         */
        @NotNull
        Builder addSlotToggleHandler(@NotNull BiConsumer<? super Integer, ? super Boolean> handler);

        @Override
        @NotNull
        Builder clone();
    }
}
