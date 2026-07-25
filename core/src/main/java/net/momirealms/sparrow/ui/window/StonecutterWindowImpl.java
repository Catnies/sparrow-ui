package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.internal.menu.StonecutterMenuHandle;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.util.MiscUtils;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class StonecutterWindowImpl extends AbstractWindow<StonecutterMenuHandle> implements StonecutterWindow {
    private final HandlerList<Consumer<StonecutterRecipeSelect>> recipeSelectHandlers;
    private volatile List<StonecutterRecipeOption> recipeOptions;
    private volatile int selectedRecipeIndex;

    StonecutterWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            @NotNull List<StonecutterRecipeOption> recipeOptions,
            int selectedRecipeIndex,
            @NotNull List<Consumer<StonecutterRecipeSelect>> recipeSelectHandlers
    ) {
        super(manager, viewer, layout, settings);
        this.recipeOptions = recipeOptions;
        this.selectedRecipeIndex = selectedRecipeIndex;
        this.recipeSelectHandlers = new HandlerList<>(recipeSelectHandlers);
    }

    @Override
    public void setRecipeOptions(@NotNull List<? extends StonecutterRecipeOption> options) {
        List<StonecutterRecipeOption> copy = StonecutterWindowImpl.copyRecipeOptions(options);
        this.submit(
                () -> {
                    this.recipeOptions = copy;
                    this.selectedRecipeIndex = -1;
                    StonecutterMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setRecipeOptions(copy);
                        this.requestSynchronize();
                    }
                },
                "Failed to replace Stonecutter Window recipe options"
        );
    }

    @Override
    @NotNull
    public List<StonecutterRecipeOption> getRecipeOptions() {
        return this.recipeOptions;
    }

    @Override
    public int getSelectedRecipeIndex() {
        return this.selectedRecipeIndex;
    }

    @Override
    public void setSelectedRecipeIndex(int index) {
        this.submit(
                () -> {
                    StonecutterWindowImpl.checkSelectedRecipeIndex(index, this.recipeOptions.size());
                    this.selectedRecipeIndex = index;
                    StonecutterMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setSelectedRecipeIndex(index);
                        this.requestSynchronize();
                    }
                },
                "Failed to update Stonecutter Window selected recipe"
        );
    }

    @Override
    public void setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super StonecutterRecipeSelect>> handlers) {
        List<Consumer<StonecutterRecipeSelect>> copy = MiscUtils.copyConsumers(handlers);
        this.submit(
                () -> this.recipeSelectHandlers.set(copy),
                "Failed to replace Stonecutter Window recipe selection handlers"
        );
    }

    @Override
    @NotNull
    public List<Consumer<StonecutterRecipeSelect>> getRecipeSelectHandlers() {
        return this.recipeSelectHandlers.snapshot();
    }

    @Override
    public void addRecipeSelectHandler(@NotNull Consumer<? super StonecutterRecipeSelect> handler) {
        Consumer<StonecutterRecipeSelect> copied = MiscUtils.narrowConsumer(handler);
        this.submit(
                () -> this.recipeSelectHandlers.append(copied),
                "Failed to add Stonecutter Window recipe selection handler"
        );
    }

    @Override
    public void removeRecipeSelectHandler(@NotNull Consumer<? super StonecutterRecipeSelect> handler) {
        this.submit(
                () -> this.recipeSelectHandlers.remove(MiscUtils.narrowConsumer(handler)),
                "Failed to remove Stonecutter Window recipe selection handler"
        );
    }

    @Override
    @NotNull
    protected StonecutterMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        StonecutterMenuHandle menuHandle = factory.stonecutter(this.viewer(), generation);
        menuHandle.setRecipeOptions(this.recipeOptions);
        menuHandle.setSelectedRecipeIndex(this.selectedRecipeIndex);
        return menuHandle;
    }

    @Override
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
        if (input instanceof MenuInput.WindowSpecific.ButtonClick buttonClick) {
            this.handleRecipeSelection(buttonClick);
        }
    }

    private void handleRecipeSelection(MenuInput.WindowSpecific.ButtonClick click) {
        StonecutterMenuHandle menuHandle = this.menuHandle();
        if (menuHandle == null || click.containerId() != menuHandle.containerId()) {
            return;
        }

        List<StonecutterRecipeOption> options = this.recipeOptions;
        int selectedIndex = click.button();
        if (selectedIndex < 0 || selectedIndex >= options.size()) {
            menuHandle.reconcileClientSelection(this.selectedRecipeIndex);
            this.requestSynchronize();
            return;
        }

        int previousIndex = this.selectedRecipeIndex;
        this.selectedRecipeIndex = selectedIndex;
        menuHandle.reconcileClientSelection(selectedIndex);
        this.requestSynchronize();

        StonecutterRecipeOption previousOption = previousIndex != -1 ? options.get(previousIndex) : null;
        StonecutterRecipeSelect selection = new StonecutterRecipeSelect(this.viewer(), this, previousIndex, selectedIndex, previousOption, options.get(selectedIndex));
        this.recipeSelectHandlers.forEachIsolated(
                handler -> handler.accept(selection),
                "Failed to handle Stonecutter Window recipe selection",
                this::report
        );
    }

    private static void checkSelectedRecipeIndex(int index, int optionCount) {
        if (index < -1 || index >= optionCount) {
            throw new IndexOutOfBoundsException(
                    "stonecutter selected recipe index out of bounds: " + index
            );
        }
    }

    @NotNull
    private static List<StonecutterRecipeOption> copyRecipeOptions(@NotNull List<? extends StonecutterRecipeOption> options) {
        Objects.requireNonNull(options, "options");
        ArrayList<StonecutterRecipeOption> copy = new ArrayList<>(options.size());
        for (StonecutterRecipeOption option : options) {
            copy.add(Objects.requireNonNull(option, "options contains null"));
        }
        return List.copyOf(copy);
    }

    static final class BuilderImpl extends AbstractWindowBuilder<StonecutterWindow, StonecutterWindow.Builder> implements StonecutterWindow.Builder {
        private Gui upperGui = Gui.empty(new GuiSize(2, 1));
        private @Nullable Gui lowerGui;
        private List<StonecutterRecipeOption> recipeOptions = List.of();
        private int selectedRecipeIndex = -1;
        private List<Consumer<StonecutterRecipeSelect>> recipeSelectHandlers = new ArrayList<>();

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperGui = source.upperGui;
            this.lowerGui = source.lowerGui;
            this.recipeOptions = source.recipeOptions;
            this.selectedRecipeIndex = source.selectedRecipeIndex;
            this.recipeSelectHandlers = new ArrayList<>(source.recipeSelectHandlers);
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = upperGui;
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder setRecipeOptions(@NotNull List<? extends StonecutterRecipeOption> options) {
            this.recipeOptions = StonecutterWindowImpl.copyRecipeOptions(options);
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder setSelectedRecipeIndex(int index) {
            this.selectedRecipeIndex = index;
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super StonecutterRecipeSelect>> handlers) {
            this.recipeSelectHandlers = new ArrayList<>(MiscUtils.copyConsumers(handlers));
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder addRecipeSelectHandler(@NotNull Consumer<? super StonecutterRecipeSelect> handler) {
            this.recipeSelectHandlers.add(MiscUtils.narrowConsumer(handler));
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected StonecutterWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected StonecutterWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.upperGui.width() != 2 || this.upperGui.height() != 1) {
                throw new IllegalArgumentException("stonecutter upper GUI must have size 2x1");
            }
            StonecutterWindowImpl.checkSelectedRecipeIndex(
                    this.selectedRecipeIndex,
                    this.recipeOptions.size()
            );
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.upperGui),
                    WindowLayout.Region.lower(this.lowerGui)
            );
            return new StonecutterWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.recipeOptions,
                    this.selectedRecipeIndex,
                    List.copyOf(this.recipeSelectHandlers)
            );
        }
    }
}
