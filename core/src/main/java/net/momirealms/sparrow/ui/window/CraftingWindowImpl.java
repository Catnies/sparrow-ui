package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.window.click.RecipeBookSelectClick;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.RecipeBookMenuHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

final class CraftingWindowImpl extends AbstractRecipeBookWindow<RecipeBookMenuHandle> implements CraftingWindow {

    CraftingWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            @NotNull List<Consumer<RecipeBookSelectClick>> recipeSelectHandlers
    ) {
        super(manager, viewer, layout, settings, recipeSelectHandlers);
    }

    @Override
    @NotNull
    protected RecipeBookMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.crafting(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractRecipeBookWindow.BuilderBase<CraftingWindow, CraftingWindow.Builder> implements CraftingWindow.Builder {
        private Pane craftingPane = Pane.empty(new PaneSize(3, 3));
        private Pane resultPane = Pane.empty(new PaneSize(1, 1));
        private @Nullable Pane lowerPane;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.craftingPane = source.craftingPane;
            this.resultPane = source.resultPane;
            this.lowerPane = source.lowerPane;
        }

        @Override
        @NotNull
        public CraftingWindow.Builder setCraftingPane(@NotNull Pane craftingPane) {
            this.craftingPane = craftingPane;
            return this;
        }

        @Override
        @NotNull
        public CraftingWindow.Builder setResultPane(@NotNull Pane resultPane) {
            this.resultPane = resultPane;
            return this;
        }

        @Override
        @NotNull
        public CraftingWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            return this;
        }

        @Override
        @NotNull
        public CraftingWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected CraftingWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected CraftingWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.craftingPane.width() != 3 || this.craftingPane.height() != 3)
                throw new IllegalArgumentException("crafting grid Pane must have size 3x3");
            if (this.resultPane.width() != 1 || this.resultPane.height() != 1)
                throw new IllegalArgumentException("crafting result Pane must have size 1x1");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.resultPane),
                    WindowLayout.Region.upper(this.craftingPane),
                    WindowLayout.Region.lower(lowerPane)
            );
            return new CraftingWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.recipeSelectHandlers()
            );
        }
    }
}
