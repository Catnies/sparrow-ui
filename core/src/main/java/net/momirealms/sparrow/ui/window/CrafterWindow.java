package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 使用原版合成器界面的十槽 Window.
 *
 * <p>原始槽位 0 到 8 为 3x3 输入区, 槽位 9 为结果. 玩家可通过原版按钮启用或禁用输入槽.</p>
 */
public interface CrafterWindow extends Window {

    /**
     * 创建使用 3x3 输入 GUI 和 1x1 结果 GUI 的 Builder.
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
     * 按 3x3 坐标设置输入槽的禁用状态.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @param disabled true 表示禁用
     */
    default void setSlotDisabled(int x, int y, boolean disabled) {
        this.setSlotDisabled(CrafterWindow.slotAt(x, y), disabled);
    }

    /**
     * 返回输入槽是否已禁用.
     *
     * @param slot 输入槽编号
     * @return 禁用时为 true
     */
    boolean isSlotDisabled(int slot);

    /**
     * 按 3x3 坐标返回输入槽是否已禁用.
     *
     * @param x 横向坐标
     * @param y 纵向坐标
     * @return 禁用时为 true
     */
    default boolean isSlotDisabled(int x, int y) {
        return this.isSlotDisabled(CrafterWindow.slotAt(x, y));
    }

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

    private static int slotAt(int x, int y) {
        if (x < 0 || x >= 3 || y < 0 || y >= 3) {
            throw new IndexOutOfBoundsException("crafter position out of bounds: (" + x + ", " + y + ")");
        }
        return x + y * 3;
    }

    /**
     * 合成器 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<CrafterWindow, Builder> {

        /**
         * 设置必须为 3x3 的输入 GUI.
         *
         * @param craftingGui 合成输入 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setCraftingGui(@NotNull Gui craftingGui);

        /**
         * 设置必须为 1x1 的结果 GUI.
         *
         * @param resultGui 结果 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setResultGui(@NotNull Gui resultGui);

        /**
         * 设置控制玩家物品栏区域的 9x4 GUI; null 表示映射玩家真实物品栏.
         *
         * @param lowerGui 下部 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerGui(@Nullable Gui lowerGui);

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
         * 按 3x3 坐标设置初始输入槽禁用状态.
         *
         * @param x 横向坐标
         * @param y 纵向坐标
         * @param disabled true 表示禁用
         * @return 此 Builder
         */
        @NotNull
        default Builder setSlotDisabled(int x, int y, boolean disabled) {
            return this.setSlotDisabled(CrafterWindow.slotAt(x, y), disabled);
        }

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
