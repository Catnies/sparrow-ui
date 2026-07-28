package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface BlastFurnaceWindow extends RecipeBookWindow {

    /**
     * 创建高炉 Window Builder.
     *
     * @return 高炉 Window Builder
     */
    @NotNull
    static Builder builder() {
        return new BlastFurnaceWindowImpl.BuilderImpl();
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
     * 高炉 Window 的可重复 Builder.
     */
    interface Builder extends RecipeBookWindow.Builder<BlastFurnaceWindow, Builder> {

        @NotNull
        Builder setInputGui(@NotNull Gui inputGui);

        @NotNull
        Builder setFuelGui(@NotNull Gui fuelGui);

        @NotNull
        Builder setResultGui(@NotNull Gui resultGui);

        @NotNull
        Builder setLowerGui(@Nullable Gui lowerGui);

        @NotNull
        Builder setCookProgress(double progress);

        @NotNull
        Builder setFuelProgress(double progress);

        @Override
        @NotNull
        Builder clone();
    }
}
