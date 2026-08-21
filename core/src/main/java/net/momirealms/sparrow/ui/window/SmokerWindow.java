package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface SmokerWindow extends RecipeBookWindow {

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

    @NotNull
    static Builder builder() {
        return new SmokerWindowImpl.BuilderImpl();
    }

    interface Builder extends RecipeBookWindow.Builder<SmokerWindow, Builder> {

        @NotNull
        Builder setInputPane(@NotNull Pane inputPane);

        @NotNull
        Builder setFuelPane(@NotNull Pane fuelPane);

        @NotNull
        Builder setResultPane(@NotNull Pane resultPane);

        @NotNull
        Builder setLowerPane(@Nullable Pane lowerPane);

        @NotNull
        Builder setCookProgress(double progress);

        @NotNull
        Builder setFuelProgress(double progress);

        @Override
        @NotNull
        Builder clone();
    }
}
