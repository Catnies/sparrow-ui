package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.window.handle.MenuFactory;
import net.momirealms.sparrow.ui.window.handle.MenuHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class HopperWindowImpl extends AbstractWindow<MenuHandle> implements HopperWindow {

    HopperWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings
    ) {
        super(manager, viewer, layout, settings);
    }

    @NotNull
    @Override
    protected MenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.hopper(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractWindowBuilder<HopperWindow, HopperWindow.Builder>
            implements HopperWindow.Builder {
        private Pane upperPane = Pane.empty(new PaneSize(5, 1));
        private @Nullable Pane lowerPane;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperPane = source.upperPane;
            this.lowerPane = source.lowerPane;
        }

        @NotNull
        @Override
        public HopperWindow.Builder setUpperPane(@NotNull Pane upperPane) {
            this.upperPane = upperPane;
            return this;
        }

        @NotNull
        @Override
        public HopperWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            return this;
        }

        @NotNull
        @Override
        public HopperWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @NotNull
        @Override
        protected HopperWindow.Builder self() {
            return this;
        }

        @NotNull
        @Override
        protected HopperWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.upperPane.width() != 5 || this.upperPane.height() != 1)
                throw new IllegalArgumentException("hopper upper Pane must have size 5x1");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.split(this.upperPane, lowerPane);
            return new HopperWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
