package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface BrewingWindow extends Window {

    /**
     * 设置箭头已经完成的酿造进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     * @throws IllegalArgumentException 进度不是有限数或超出范围时
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
     * @throws IllegalArgumentException 进度不是有限数或超出范围时
     */
    void setFuelProgress(double progress);

    /**
     * 返回最近一次已应用的燃料进度.
     *
     * @return 范围为 0.0 到 1.0 的进度
     */
    double getFuelProgress();

    @NotNull
    static Builder builder() {
        return new BrewingWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<BrewingWindow, Builder> {

        /**
         * 设置映射协议槽位(raw slot)3 的 1x1 原料 Pane.
         *
         * @param inputPane 原料 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setInputPane(@NotNull Pane inputPane);

        /**
         * 设置映射协议槽位(raw slot)4 的 1x1 燃料 Pane.
         *
         * @param fuelPane 燃料 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setFuelPane(@NotNull Pane fuelPane);

        /**
         * 设置映射协议槽位(raw slot)0 到 2 的 3x1 结果 Pane.
         *
         * @param resultPane 结果 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setResultPane(@NotNull Pane resultPane);

        /**
         * 设置控制玩家物品栏区域的 9x4 Pane, null 表示连接玩家 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerPane(@Nullable Pane lowerPane);

        /**
         * 设置初始酿造进度.
         *
         * @param progress 范围为 0.0 到 1.0 的进度
         * @return 此 Builder
         * @throws IllegalArgumentException 进度不是有限数或超出范围时
         */
        @NotNull
        Builder setBrewProgress(double progress);

        /**
         * 设置初始燃料进度.
         *
         * @param progress 范围为 0.0 到 1.0 的进度
         * @return 此 Builder
         * @throws IllegalArgumentException 进度不是有限数或超出范围时
         */
        @NotNull
        Builder setFuelProgress(double progress);

        @Override
        @NotNull
        Builder clone();
    }
}
