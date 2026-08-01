package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.click.RecipeBookSelectClick;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.FurnaceMenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 三种原版炉类 Window 共用的布局, 进度与 Builder 实现.
 */
abstract class AbstractFurnaceWindow extends AbstractRecipeBookWindow<FurnaceMenuHandle> {
    private volatile double cookProgress;
    private volatile double fuelProgress;

    AbstractFurnaceWindow(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            @NotNull List<Consumer<RecipeBookSelectClick>> recipeSelectHandlers,
            double cookProgress,
            double fuelProgress
    ) {
        super(manager, viewer, layout, settings, recipeSelectHandlers);
        this.cookProgress = cookProgress;
        this.fuelProgress = fuelProgress;
    }

    public final void setCookProgress(double progress) {
        if (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0) {
            throw new IllegalArgumentException("cook progress must be between 0.0 and 1.0: " + progress);
        }
        this.submit(
                () -> {
                    this.cookProgress = progress;
                    FurnaceMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setCookProgress(progress);
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update furnace cook progress"
        );
    }

    public final double getCookProgress() {
        return this.cookProgress;
    }

    public final void setFuelProgress(double progress) {
        if (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0) {
            throw new IllegalArgumentException("fuel progress must be between 0.0 and 1.0: " + progress);
        }
        this.submit(
                () -> {
                    this.fuelProgress = progress;
                    FurnaceMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setFuelProgress(progress);
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update furnace fuel progress"
        );
    }

    public final double getFuelProgress() {
        return this.fuelProgress;
    }

    @Override
    @NotNull
    protected final FurnaceMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        FurnaceMenuHandle menuHandle = this.createFurnaceMenuHandle(factory, generation);
        menuHandle.setCookProgress(this.cookProgress);
        menuHandle.setFuelProgress(this.fuelProgress);
        return menuHandle;
    }

    @NotNull
    protected abstract FurnaceMenuHandle createFurnaceMenuHandle(@NotNull MenuFactory factory, long generation);

    /**
     * 三种炉类 Window Builder 共用的槽位与进度实现.
     */
    abstract static class BuilderBase<W extends RecipeBookWindow, B extends RecipeBookWindow.Builder<W, B>> extends AbstractRecipeBookWindow.BuilderBase<W, B> {
        private Gui inputGui = Gui.empty(new GuiSize(1, 1));
        private Gui fuelGui = Gui.empty(new GuiSize(1, 1));
        private Gui resultGui = Gui.empty(new GuiSize(1, 1));
        private @Nullable Gui lowerGui;
        private double cookProgress;
        private double fuelProgress;

        BuilderBase() {
        }

        BuilderBase(@NotNull BuilderBase<W, B> source) {
            super(source);
            this.inputGui = source.inputGui;
            this.fuelGui = source.fuelGui;
            this.resultGui = source.resultGui;
            this.lowerGui = source.lowerGui;
            this.cookProgress = source.cookProgress;
            this.fuelProgress = source.fuelProgress;
        }

        @NotNull
        public final B setInputGui(@NotNull Gui inputGui) {
            this.inputGui = inputGui;
            return this.self();
        }

        @NotNull
        public final B setFuelGui(@NotNull Gui fuelGui) {
            this.fuelGui = fuelGui;
            return this.self();
        }

        @NotNull
        public final B setResultGui(@NotNull Gui resultGui) {
            this.resultGui = resultGui;
            return this.self();
        }

        @NotNull
        public final B setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
            return this.self();
        }

        @NotNull
        public final B setCookProgress(double progress) {
            if (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0) {
                throw new IllegalArgumentException("cook progress must be between 0.0 and 1.0: " + progress);
            }
            this.cookProgress = progress;
            return this.self();
        }

        @NotNull
        public final B setFuelProgress(double progress) {
            if (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0) {
                throw new IllegalArgumentException("fuel progress must be between 0.0 and 1.0: " + progress);
            }
            this.fuelProgress = progress;
            return this.self();
        }

        @Override
        @NotNull
        protected final W createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.inputGui.width() != 1 || this.inputGui.height() != 1)
                throw new IllegalArgumentException("input GUI must have size 1x1");
            if (this.fuelGui.width() != 1 || this.fuelGui.height() != 1)
                throw new IllegalArgumentException("fuel GUI must have size 1x1");
            if (this.resultGui.width() != 1 || this.resultGui.height() != 1)
                throw new IllegalArgumentException("result GUI must have size 1x1");

            Gui lowerGui = this.lowerGui == null ? viewerReferencingInventory(viewer) : this.lowerGui;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.inputGui),
                    WindowLayout.Region.upper(this.fuelGui),
                    WindowLayout.Region.upper(this.resultGui),
                    WindowLayout.Region.lower(lowerGui)
            );
            return this.createFurnaceWindow(
                    viewer,
                    layout,
                    settings,
                    this.recipeSelectHandlers(),
                    this.cookProgress,
                    this.fuelProgress
            );
        }

        @NotNull
        protected abstract W createFurnaceWindow(
                @NotNull Player viewer,
                @NotNull WindowLayout layout,
                @NotNull AbstractWindow.Settings settings,
                @NotNull List<Consumer<RecipeBookSelectClick>> recipeSelectHandlers,
                double cookProgress,
                double fuelProgress
        );
    }
}
