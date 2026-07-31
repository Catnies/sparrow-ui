package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.BrewingMenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class BrewingWindowImpl extends AbstractWindow<BrewingMenuHandle> implements BrewingWindow {
    private volatile double brewProgress;
    private volatile double fuelProgress;

    BrewingWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            double brewProgress,
            double fuelProgress
    ) {
        super(manager, viewer, layout, settings);
        this.brewProgress = brewProgress;
        this.fuelProgress = fuelProgress;
    }

    @Override
    public void setBrewProgress(double progress) {
        BrewingWindowImpl.requireProgress(progress, "brew");
        this.submit(
                () -> {
                    this.brewProgress = progress;
                    BrewingMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setBrewProgress(progress);
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update Brewing Window brew progress"
        );
    }

    @Override
    public double getBrewProgress() {
        return this.brewProgress;
    }

    @Override
    public void setFuelProgress(double progress) {
        BrewingWindowImpl.requireProgress(progress, "fuel");
        this.submit(
                () -> {
                    this.fuelProgress = progress;
                    BrewingMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setFuelProgress(progress);
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update Brewing Window fuel progress"
        );
    }

    @Override
    public double getFuelProgress() {
        return this.fuelProgress;
    }

    @Override
    @NotNull
    protected BrewingMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        BrewingMenuHandle menu = factory.brewing(this.viewer(), generation);
        menu.setBrewProgress(this.brewProgress);
        menu.setFuelProgress(this.fuelProgress);
        return menu;
    }

    private static void requireProgress(double progress, @NotNull String name) {
        if (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0) {
            throw new IllegalArgumentException(name + " progress must be between 0.0 and 1.0: " + progress);
        }
    }

    static final class BuilderImpl extends AbstractWindowBuilder<BrewingWindow, BrewingWindow.Builder> implements BrewingWindow.Builder {
        private Gui inputGui = Gui.empty(new GuiSize(1, 1));
        private Gui fuelGui = Gui.empty(new GuiSize(1, 1));
        private Gui resultGui = Gui.empty(new GuiSize(3, 1));
        private @Nullable Gui lowerGui;
        private double brewProgress;
        private double fuelProgress;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.inputGui = source.inputGui;
            this.fuelGui = source.fuelGui;
            this.resultGui = source.resultGui;
            this.lowerGui = source.lowerGui;
            this.brewProgress = source.brewProgress;
            this.fuelProgress = source.fuelProgress;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setInputGui(@NotNull Gui inputGui) {
            this.inputGui = inputGui;
            return this;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setFuelGui(@NotNull Gui fuelGui) {
            this.fuelGui = fuelGui;
            return this;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setResultGui(@NotNull Gui resultGui) {
            this.resultGui = resultGui;
            return this;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
            return this;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setBrewProgress(double progress) {
            BrewingWindowImpl.requireProgress(progress, "brew");
            this.brewProgress = progress;
            return this;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setFuelProgress(double progress) {
            BrewingWindowImpl.requireProgress(progress, "fuel");
            this.fuelProgress = progress;
            return this;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected BrewingWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected BrewingWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.inputGui.width() != 1 || this.inputGui.height() != 1)
                throw new IllegalArgumentException("brewing input GUI must have size 1x1");
            if (this.fuelGui.width() != 1 || this.fuelGui.height() != 1)
                throw new IllegalArgumentException("brewing fuel GUI must have size 1x1");
            if (this.resultGui.width() != 3 || this.resultGui.height() != 1)
                throw new IllegalArgumentException("brewing result GUI must have size 3x1");

            Gui lowerGui = this.lowerGui == null ? viewerReferencingInventory(viewer) : this.lowerGui;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.resultGui),
                    WindowLayout.Region.upper(this.inputGui),
                    WindowLayout.Region.upper(this.fuelGui),
                    WindowLayout.Region.lower(lowerGui)
            );
            return new BrewingWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.brewProgress,
                    this.fuelProgress
            );
        }
    }
}
