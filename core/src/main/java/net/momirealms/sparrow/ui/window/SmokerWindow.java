package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface SmokerWindow extends RecipeBookWindow {

    /**
     * 设置箭头上那根烹饪进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     * @throws IllegalArgumentException 进度不是有限数, 或者超出范围时
     */
    void setCookProgress(double progress);

    /**
     * 最近一次在玩家实体线程上应用过的烹饪进度.
     *
     * @return 范围为 0.0 到 1.0 的进度
     */
    double getCookProgress();

    /**
     * 设置火焰那根进度, 也就是剩下的燃料还剩多少.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     * @throws IllegalArgumentException 进度不是有限数, 或者超出范围时
     */
    void setFuelProgress(double progress);

    /**
     * 最近一次在玩家实体线程上应用过的燃料进度.
     *
     * @return 范围为 0.0 到 1.0 的进度
     */
    double getFuelProgress();

    @NotNull
    static Builder builder() {
        return new SmokerWindowImpl.BuilderImpl();
    }

    interface Builder extends RecipeBookWindow.Builder<SmokerWindow, Builder> {

        /**
         * 设置输入 Pane, 它映射到协议槽位(raw slot)0, 尺寸 1x1.
         *
         * @param inputPane 输入 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setInputPane(@NotNull Pane inputPane);

        /**
         * 设置燃料 Pane, 映射协议槽位(raw slot)1, 尺寸 1x1.
         *
         * @param fuelPane 燃料 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setFuelPane(@NotNull Pane fuelPane);

        /**
         * 设置结果 Pane, 映射协议槽位(raw slot)2, 尺寸 1x1.
         *
         * @param resultPane 结果 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setResultPane(@NotNull Pane resultPane);

        /**
         * 设置下部那个 9x4 的 Pane, 管玩家物品栏那一片; 给 null 就接玩家的 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerPane(@Nullable Pane lowerPane);

        /**
         * 设置一开始的烹饪进度.
         *
         * @param progress 范围为 0.0 到 1.0 的进度
         * @return 此 Builder
         * @throws IllegalArgumentException 进度不是有限数, 或者超出范围时
         */
        @NotNull
        Builder setCookProgress(double progress);

        /**
         * 设置一开始的燃料进度.
         *
         * @param progress 范围为 0.0 到 1.0 的进度
         * @return 此 Builder
         * @throws IllegalArgumentException 进度不是有限数, 或者超出范围时
         */
        @NotNull
        Builder setFuelProgress(double progress);

        @Override
        @NotNull
        Builder clone();
    }
}
