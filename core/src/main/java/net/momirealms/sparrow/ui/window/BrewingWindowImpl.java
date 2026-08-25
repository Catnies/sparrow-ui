package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.window.handle.BrewingMenuHandle;
import net.momirealms.sparrow.ui.window.handle.MenuFactory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class BrewingWindowImpl extends AbstractWindow<BrewingMenuHandle> implements BrewingWindow {
    private volatile double brewProgress; // 酿造进度, 0.0-1.0
    private volatile double fuelProgress; // 燃料进度, 0.0-1.0

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
        private Pane inputPane = Pane.empty(new PaneSize(1, 1));    // 协议槽位 3 的原料格
        private Pane fuelPane = Pane.empty(new PaneSize(1, 1));     // 协议槽位 4 的燃料格
        private Pane resultPane = Pane.empty(new PaneSize(3, 1));   // 协议槽位 0-2 的结果格
        private @Nullable Pane lowerPane;                           // 玩家物品栏, null 时接管 Bukkit 背包
        private double brewProgress;                                // 初始酿造进度
        private double fuelProgress;                                // 初始燃料进度

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.inputPane = source.inputPane;
            this.fuelPane = source.fuelPane;
            this.resultPane = source.resultPane;
            this.lowerPane = source.lowerPane;
            this.brewProgress = source.brewProgress;
            this.fuelProgress = source.fuelProgress;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setInputPane(@NotNull Pane inputPane) {
            this.inputPane = inputPane;
            return this;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setFuelPane(@NotNull Pane fuelPane) {
            this.fuelPane = fuelPane;
            return this;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setResultPane(@NotNull Pane resultPane) {
            this.resultPane = resultPane;
            return this;
        }

        @Override
        @NotNull
        public BrewingWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
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
            if (this.inputPane.width() != 1 || this.inputPane.height() != 1)
                throw new IllegalArgumentException("brewing input Pane must have size 1x1");
            if (this.fuelPane.width() != 1 || this.fuelPane.height() != 1)
                throw new IllegalArgumentException("brewing fuel Pane must have size 1x1");
            if (this.resultPane.width() != 3 || this.resultPane.height() != 1)
                throw new IllegalArgumentException("brewing result Pane must have size 3x1");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.resultPane),
                    WindowLayout.Region.upper(this.inputPane),
                    WindowLayout.Region.upper(this.fuelPane),
                    WindowLayout.Region.lower(lowerPane)
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
