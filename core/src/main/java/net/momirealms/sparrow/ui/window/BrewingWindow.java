package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface BrewingWindow extends Window {

    /**
     * 创建使用 3x1 结果 GUI, 1x1 原料 GUI 与 1x1 燃料 GUI 的 Builder.
     *
     * @return 酿造台窗口 Builder
     */
    @NotNull
    static Builder builder() {
        return new BrewingWindowImpl.BuilderImpl();
    }

    /**
     * 设置箭头已经完成的酿造进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     */
    void setBrewProgress(double progress);

    /**
     * 返回最近一次已应用的酿造进度.
     *
     * @return 范围为 0.0 到 1.0 的进度
     */
    double getBrewProgress();

    /**
     * 设置燃料条填充进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     */
    void setFuelProgress(double progress);

    /**
     * 返回最近一次已应用的燃料进度.
     *
     * @return 范围为 0.0 到 1.0 的进度
     */
    double getFuelProgress();

    /**
     * 酿造台 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<BrewingWindow, Builder> {

        /**
         * 设置映射协议槽位(raw slot)3 的 1x1 原料 GUI.
         *
         * @param inputGui 原料 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setInputGui(@NotNull Gui inputGui);

        /**
         * 设置映射协议槽位(raw slot)4 的 1x1 燃料 GUI.
         *
         * @param fuelGui 燃料 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setFuelGui(@NotNull Gui fuelGui);

        /**
         * 设置映射协议槽位(raw slot)0 到 2 的 3x1 结果 GUI.
         *
         * @param resultGui 结果 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setResultGui(@NotNull Gui resultGui);

        /**
         * 设置控制玩家物品栏区域的 9x4 GUI; null 表示连接玩家 Bukkit Inventory.
         *
         * @param lowerGui 下部 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerGui(@Nullable Gui lowerGui);

        /**
         * 设置初始酿造进度.
         *
         * @param progress 范围为 0.0 到 1.0 的进度
         * @return 此 Builder
         */
        @NotNull
        Builder setBrewProgress(double progress);

        /**
         * 设置初始燃料进度.
         *
         * @param progress 范围为 0.0 到 1.0 的进度
         * @return 此 Builder
         */
        @NotNull
        Builder setFuelProgress(double progress);

        @Override
        @NotNull
        Builder clone();
    }
}
