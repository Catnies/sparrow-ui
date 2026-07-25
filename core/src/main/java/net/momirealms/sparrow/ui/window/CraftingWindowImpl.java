package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.key.Key;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.CraftingMenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.util.MiscUtils;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

final class CraftingWindowImpl extends AbstractWindow<CraftingMenuHandle> implements CraftingWindow {
    private volatile List<Consumer<RecipeBookSelect>> recipeSelectHandlers;

    CraftingWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            @NotNull List<Consumer<RecipeBookSelect>> recipeSelectHandlers
    ) {
        super(manager, viewer, layout, settings);
        this.recipeSelectHandlers = recipeSelectHandlers;
    }

    @Override
    @NotNull
    public CompletionStage<GhostRecipeResult> sendGhostRecipe(@NotNull Key recipeId) {
        return this.submit(
                () -> {
                    CraftingMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle == null) {
                        return GhostRecipeResult.WINDOW_CLOSED;
                    }
                    return menuHandle.sendGhostRecipe(recipeId)
                            ? GhostRecipeResult.SENT
                            : GhostRecipeResult.RECIPE_NOT_FOUND;
                },
                () -> GhostRecipeResult.VIEWER_UNAVAILABLE
        );
    }

    @Override
    public void setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super RecipeBookSelect>> handlers) {
        List<Consumer<RecipeBookSelect>> copy = MiscUtils.copyConsumers(handlers);
        this.submit(
                () -> this.recipeSelectHandlers = copy,
                "Failed to replace Crafting Window recipe selection handlers"
        );
    }

    @Override
    @NotNull
    public List<Consumer<RecipeBookSelect>> getRecipeSelectHandlers() {
        return this.recipeSelectHandlers;
    }

    @Override
    public void addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelect> handler) {
        Consumer<RecipeBookSelect> copied = MiscUtils.narrowConsumer(handler);
        this.submit(
                () -> this.recipeSelectHandlers = MiscUtils.append(this.recipeSelectHandlers, copied),
                "Failed to add Crafting Window recipe selection handler"
        );
    }

    @Override
    public void removeRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelect> handler) {
        this.submit(
                () -> this.recipeSelectHandlers = MiscUtils.removeConsumer(this.recipeSelectHandlers, handler),
                "Failed to remove Crafting Window recipe selection handler"
        );
    }

    @Override
    @NotNull
    protected CraftingMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.crafting(this.viewer(), generation);
    }

    @Override
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
        if (input instanceof MenuInput.WindowSpecific.RecipePlace recipePlace) {
            this.handleRecipeSelection(recipePlace);
        }
    }

    private void handleRecipeSelection(MenuInput.WindowSpecific.RecipePlace recipePlace) {
        CraftingMenuHandle menuHandle = this.menuHandle();
        if (menuHandle == null || recipePlace.containerId() != menuHandle.containerId() || this.viewer().getGameMode() == GameMode.SPECTATOR) return;
        Key recipeId = menuHandle.recipeKey(recipePlace.displayId());
        if (recipeId == null) return;
        NamespacedKey bukkitRecipeId = NamespacedKey.fromString(recipeId.asString());
        if (bukkitRecipeId == null || !this.viewer().hasDiscoveredRecipe(bukkitRecipeId)) return;

        RecipeBookSelect selection = new RecipeBookSelect(this.viewer(), this, recipeId, recipePlace.makeAll());
        List<Consumer<RecipeBookSelect>> handlers = this.recipeSelectHandlers;
        for (int index = 0; index < handlers.size(); index++) {
            try {
                handlers.get(index).accept(selection);
            } catch (Throwable throwable) {
                this.report("Failed to handle Crafting Window recipe selection", throwable);
            }
        }
    }

    static final class BuilderImpl extends AbstractWindowBuilder<CraftingWindow, CraftingWindow.Builder> implements CraftingWindow.Builder {
        private Gui craftingGui = Gui.empty(new GuiSize(3, 3));
        private Gui resultGui = Gui.empty(new GuiSize(1, 1));
        private @Nullable Gui lowerGui;
        private List<Consumer<RecipeBookSelect>> recipeSelectHandlers = new ArrayList<>();

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.craftingGui = source.craftingGui;
            this.resultGui = source.resultGui;
            this.lowerGui = source.lowerGui;
            this.recipeSelectHandlers = new ArrayList<>(source.recipeSelectHandlers);
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
        public CraftingWindow.Builder setRecipeSelectHandlers(
                @NotNull List<? extends Consumer<? super RecipeBookSelect>> handlers
        ) {
            this.recipeSelectHandlers = new ArrayList<>(MiscUtils.copyConsumers(handlers));
            return this;
        }

        @Override
        @NotNull
        public CraftingWindow.Builder addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelect> handler) {
            this.recipeSelectHandlers.add(MiscUtils.narrowConsumer(handler));
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
                    List.copyOf(this.recipeSelectHandlers)
            );
        }
    }
}
