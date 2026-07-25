package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.internal.menu.FurnaceMenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

final class FurnaceWindowImpl extends AbstractFurnaceWindow implements FurnaceWindow {
    FurnaceWindowImpl(
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
        return factory.furnace(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractFurnaceWindow.BuilderBase<FurnaceWindow, FurnaceWindow.Builder> implements FurnaceWindow.Builder {
        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
        }

        @Override
        @NotNull
        public FurnaceWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected FurnaceWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected FurnaceWindow newWindow(
                @NotNull Player viewer,
                @NotNull WindowLayout layout,
                @NotNull AbstractWindow.Settings settings,
                @NotNull List<Consumer<RecipeBookSelect>> recipeSelectHandlers,
                double cookProgress,
                double fuelProgress
        ) {
            return new FurnaceWindowImpl(
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
