package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.internal.menu.FurnaceMenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

final class SmokerWindowImpl extends AbstractFurnaceWindow implements SmokerWindow {
    SmokerWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            @NotNull List<Consumer<RecipeBookSelect>> recipeSelectHandlers,
            double cookProgress,
            double fuelProgress
    ) {
        super(manager, viewer, layout, settings, recipeSelectHandlers, cookProgress, fuelProgress);
    }

    @Override
    @NotNull
    protected FurnaceMenuHandle createFurnaceMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.smoker(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractFurnaceWindow.BuilderBase<SmokerWindow, SmokerWindow.Builder> implements SmokerWindow.Builder {
        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
        }

        @Override
        @NotNull
        public SmokerWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected SmokerWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected SmokerWindow newWindow(
                @NotNull Player viewer,
                @NotNull WindowLayout layout,
                @NotNull AbstractWindow.Settings settings,
                @NotNull List<Consumer<RecipeBookSelect>> recipeSelectHandlers,
                double cookProgress,
                double fuelProgress
        ) {
            return new SmokerWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    recipeSelectHandlers,
                    cookProgress,
                    fuelProgress
            );
        }
    }
}
