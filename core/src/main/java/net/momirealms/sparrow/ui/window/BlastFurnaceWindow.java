package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 使用原版高炉界面的三槽配方书 Window.
 *
 * <p>raw slot {@code 0} 是原料, {@code 1} 是燃料, {@code 2} 是结果.
 * 烹饪与燃烧进度只负责客户端展示, 物品消耗和结果计算始终由应用维护.</p>
 *
 * <p>进度与 Builder 契约和 {@link FurnaceWindow} 相同.</p>
 */
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

    void setCookProgress(double progress);

    double getCookProgress();

    void setFuelProgress(double progress);

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
