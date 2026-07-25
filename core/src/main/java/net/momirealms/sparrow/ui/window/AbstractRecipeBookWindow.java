package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.key.Key;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.internal.menu.RecipeBookMenuHandle;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.util.MiscUtils;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 工作台与炉类 Window 共用的配方书命令和选择发布实现.
 *
 * @param <M> 具体配方书菜单句柄
 */
abstract class AbstractRecipeBookWindow<M extends RecipeBookMenuHandle> extends AbstractWindow<M> implements RecipeBookWindow {
    private final HandlerList<Consumer<RecipeBookSelect>> recipeSelectHandlers;

    AbstractRecipeBookWindow(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            @NotNull List<Consumer<RecipeBookSelect>> recipeSelectHandlers
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
    public final void setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super RecipeBookSelect>> handlers) {
        List<Consumer<RecipeBookSelect>> copy = MiscUtils.copyConsumers(handlers);
        this.submit(
                () -> this.recipeSelectHandlers.set(copy),
                "Failed to replace RecipeBook Window recipe selection handlers"
        );
    }

    @Override
    @NotNull
    public final List<Consumer<RecipeBookSelect>> getRecipeSelectHandlers() {
        return this.recipeSelectHandlers.snapshot();
    }

    @Override
    public final void addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelect> handler) {
        Consumer<RecipeBookSelect> copied = MiscUtils.narrowConsumer(handler);
        this.submit(
                () -> this.recipeSelectHandlers.append(copied),
                "Failed to add RecipeBook Window recipe selection handler"
        );
    }

    @Override
    public final void removeRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelect> handler) {
        this.submit(
                () -> this.recipeSelectHandlers.remove(MiscUtils.narrowConsumer(handler)),
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

        RecipeBookSelect selection = new RecipeBookSelect(this.viewer(), this, recipeId, recipePlace.makeAll());
        this.recipeSelectHandlers.forEachIsolated(
                handler -> handler.accept(selection),
                "Failed to handle RecipeBook Window recipe selection",
                this::report
        );
    }

    /**
     * 配方书 Window Builder 共用的处理器快照实现.
     */
    abstract static class BuilderBase<W extends RecipeBookWindow, B extends RecipeBookWindow.Builder<W, B>> extends AbstractWindowBuilder<W, B> implements RecipeBookWindow.Builder<W, B> {
        private List<Consumer<RecipeBookSelect>> recipeSelectHandlers = new ArrayList<>();

        BuilderBase() {
        }

        BuilderBase(@NotNull BuilderBase<W, B> source) {
            super(source);
            this.recipeSelectHandlers = new ArrayList<>(source.recipeSelectHandlers);
        }

        @Override
        @NotNull
        public final B setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super RecipeBookSelect>> handlers) {
            this.recipeSelectHandlers = new ArrayList<>(MiscUtils.copyConsumers(handlers));
            return this.self();
        }

        @Override
        @NotNull
        public final B addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelect> handler) {
            this.recipeSelectHandlers.add(MiscUtils.narrowConsumer(handler));
            return this.self();
        }

        @NotNull
        @Unmodifiable
        protected final List<Consumer<RecipeBookSelect>> recipeSelectHandlers() {
            return List.copyOf(this.recipeSelectHandlers);
        }
    }
}
