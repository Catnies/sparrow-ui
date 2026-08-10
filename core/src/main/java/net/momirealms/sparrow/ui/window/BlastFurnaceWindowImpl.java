package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.window.click.RecipeBookSelectClick;
import net.momirealms.sparrow.ui.internal.menu.FurnaceMenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

final class BlastFurnaceWindowImpl extends AbstractFurnaceWindow implements BlastFurnaceWindow {
    BlastFurnaceWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            @NotNull List<Consumer<RecipeBookSelectClick>> recipeSelectHandlers,
            double cookProgress,
            double fuelProgress
    ) {
        super(manager, viewer, layout, settings, recipeSelectHandlers, cookProgress, fuelProgress);
    }

    @Override
    @NotNull
    protected FurnaceMenuHandle createFurnaceMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.blastFurnace(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractFurnaceWindow.BuilderBase<BlastFurnaceWindow, BlastFurnaceWindow.Builder> implements BlastFurnaceWindow.Builder {
        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
        }

        @Override
        @NotNull
        public BlastFurnaceWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected BlastFurnaceWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected BlastFurnaceWindow createFurnaceWindow(
                @NotNull Player viewer,
                @NotNull WindowLayout layout,
                @NotNull AbstractWindow.Settings settings,
                @NotNull List<Consumer<RecipeBookSelectClick>> recipeSelectHandlers,
                double cookProgress,
                double fuelProgress
        ) {
            return new BlastFurnaceWindowImpl(
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
