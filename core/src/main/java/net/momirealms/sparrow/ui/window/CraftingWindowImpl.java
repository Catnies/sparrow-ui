package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
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
            @NotNull List<Consumer<RecipeBookSelect>> recipeSelectHandlers
    ) {
        super(manager, viewer, layout, settings, recipeSelectHandlers);
    }

    @Override
    @NotNull
    protected RecipeBookMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.crafting(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractRecipeBookWindow.BuilderBase<CraftingWindow, CraftingWindow.Builder> implements CraftingWindow.Builder {
        private Gui craftingGui = Gui.empty(new GuiSize(3, 3));
        private Gui resultGui = Gui.empty(new GuiSize(1, 1));
        private @Nullable Gui lowerGui;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.craftingGui = source.craftingGui;
            this.resultGui = source.resultGui;
            this.lowerGui = source.lowerGui;
        }

        @Override
        @NotNull
        public CraftingWindow.Builder setCraftingGui(@NotNull Gui craftingGui) {
            this.craftingGui = craftingGui;
            return this;
        }

        @Override
        @NotNull
        public CraftingWindow.Builder setResultGui(@NotNull Gui resultGui) {
            this.resultGui = resultGui;
            return this;
        }

        @Override
        @NotNull
        public CraftingWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
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
            if (this.craftingGui.width() != 3 || this.craftingGui.height() != 3) {
                throw new IllegalArgumentException("crafting grid GUI must have size 3x3");
            }
            if (this.resultGui.width() != 1 || this.resultGui.height() != 1) {
                throw new IllegalArgumentException("crafting result GUI must have size 1x1");
            }
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.resultGui),
                    WindowLayout.Region.upper(this.craftingGui),
                    WindowLayout.Region.lower(this.lowerGui)
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
