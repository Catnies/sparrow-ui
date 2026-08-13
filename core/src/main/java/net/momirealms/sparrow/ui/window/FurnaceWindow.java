package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface FurnaceWindow extends RecipeBookWindow {

    /**
     * 创建熔炉 Window Builder.
     *
     * @return 熔炉 Window Builder
     */
    @NotNull
    static Builder builder() {
        return new FurnaceWindowImpl.BuilderImpl();
    }

    /**
     * 设置箭头已经完成的烹饪进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     */
    void setCookProgress(double progress);

    /**
     * 返回最近一次已在玩家实体线程应用的烹饪进度.
     *
     * @return 范围为 0.0 到 1.0 的进度
     */
    double getCookProgress();

    /**
     * 设置剩余燃烧火焰的填充进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     */
    void setFuelProgress(double progress);

    /**
     * 返回最近一次已在玩家实体线程应用的剩余燃烧进度.
     *
     * @return 范围为 0.0 到 1.0 的进度
     */
    double getFuelProgress();

    /**
     * 熔炉 Window 的可重复 Builder.
     */
    interface Builder extends RecipeBookWindow.Builder<FurnaceWindow, Builder> {

        /**
         * 设置映射协议槽位(raw slot)0 的 1x1 输入 Pane.
         *
         * @param inputPane 输入 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setInputPane(@NotNull Pane inputPane);

        /**
         * 设置映射协议槽位(raw slot)1 的 1x1 燃料 Pane.
         *
         * @param fuelPane 燃料 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setFuelPane(@NotNull Pane fuelPane);

        /**
         * 设置映射协议槽位(raw slot)2 的 1x1 结果 Pane.
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
         * 设置初始烹饪进度.
         *
         * @param progress 范围为 0.0 到 1.0 的进度
         * @return 此 Builder
         */
        @NotNull
        Builder setCookProgress(double progress);

        /**
         * 设置初始剩余燃烧进度.
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
