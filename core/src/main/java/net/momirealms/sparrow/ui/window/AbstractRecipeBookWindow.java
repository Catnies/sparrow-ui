package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.key.Key;
import net.momirealms.sparrow.ui.click.RecipeBookSelectClick;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.internal.menu.RecipeBookMenuHandle;
import net.momirealms.sparrow.ui.util.HandlerList;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

abstract class AbstractRecipeBookWindow<M extends RecipeBookMenuHandle> extends AbstractWindow<M> implements RecipeBookWindow {
    private final HandlerList<Consumer<RecipeBookSelectClick>> recipeSelectHandlers;

    AbstractRecipeBookWindow(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            @NotNull List<Consumer<RecipeBookSelectClick>> recipeSelectHandlers
    ) {
        super(manager, viewer, layout, settings);
        this.recipeSelectHandlers = new HandlerList<>(recipeSelectHandlers);
    }

    @Override
    @NotNull
    public final CompletionStage<GhostRecipeResult> sendGhostRecipe(@NotNull Key recipeId) {
        return this.submit(
                () -> {
                    M menuHandle = this.menuHandle();
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
    public final void setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super RecipeBookSelectClick>> handlers) {
        List<Consumer<RecipeBookSelectClick>> copy = HandlerList.copyConsumers(handlers);
        this.submit(
                () -> this.recipeSelectHandlers.set(copy),
                "Failed to replace RecipeBook Window recipe selection handlers"
        );
    }

    @Override
    @NotNull
    public final List<Consumer<RecipeBookSelectClick>> getRecipeSelectHandlers() {
        return this.recipeSelectHandlers.snapshot();
    }

    @Override
    public final void addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelectClick> handler) {
        Consumer<RecipeBookSelectClick> copied = HandlerList.narrowConsumer(handler);
        this.submit(
                () -> this.recipeSelectHandlers.append(copied),
                "Failed to add RecipeBook Window recipe selection handler"
        );
    }

    @Override
    public final void removeRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelectClick> handler) {
        this.submit(
                () -> this.recipeSelectHandlers.remove(HandlerList.narrowConsumer(handler)),
                "Failed to remove RecipeBook Window recipe selection handler"
        );
    }

    @Override
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
        if (input instanceof MenuInput.WindowSpecific.RecipePlace recipePlace) {
            this.handleRecipeSelection(recipePlace);
        }
    }

    private void handleRecipeSelection(MenuInput.WindowSpecific.RecipePlace recipePlace) {
        M menuHandle = this.menuHandle();
        if (menuHandle == null
                || recipePlace.containerId() != menuHandle.containerId()
                || this.viewer().getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Key recipeId = menuHandle.recipeKey(recipePlace.displayId());
        if (recipeId == null) {
            return;
        }
        NamespacedKey bukkitRecipeId = NamespacedKey.fromString(recipeId.asString());
        if (bukkitRecipeId == null || !this.viewer().hasDiscoveredRecipe(bukkitRecipeId)) {
            return;
        }

        RecipeBookSelectClick selection = new RecipeBookSelectClick(this.viewer(), this, recipeId, recipePlace.makeAll());
        this.recipeSelectHandlers.forEachIsolated(
                handler -> handler.accept(selection),
                "Failed to handle RecipeBook Window recipe selection",
                this::report
        );
    }

    abstract static class BuilderBase<W extends RecipeBookWindow, B extends RecipeBookWindow.Builder<W, B>> extends AbstractWindowBuilder<W, B> implements RecipeBookWindow.Builder<W, B> {
        private List<Consumer<RecipeBookSelectClick>> recipeSelectHandlers = new ArrayList<>();

        BuilderBase() {
        }

        BuilderBase(@NotNull BuilderBase<W, B> source) {
            super(source);
            this.recipeSelectHandlers = new ArrayList<>(source.recipeSelectHandlers);
        }

        @Override
        @NotNull
        public final B setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super RecipeBookSelectClick>> handlers) {
            this.recipeSelectHandlers = new ArrayList<>(HandlerList.copyConsumers(handlers));
            return this.self();
        }

        @Override
        @NotNull
        public final B addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelectClick> handler) {
            this.recipeSelectHandlers.add(HandlerList.narrowConsumer(handler));
            return this.self();
        }

        @NotNull
        @Unmodifiable
        protected final List<Consumer<RecipeBookSelectClick>> recipeSelectHandlers() {
            return List.copyOf(this.recipeSelectHandlers);
        }
    }
}
