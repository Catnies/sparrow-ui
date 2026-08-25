package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.window.handle.MenuFactory;
import net.momirealms.sparrow.ui.window.handle.MenuHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class DispenserWindowImpl extends AbstractWindow<MenuHandle> implements DispenserWindow {

    DispenserWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings
    ) {
        super(manager, viewer, layout, settings);
    }

    @Override
    @NotNull
    protected MenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.dispenser(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractWindowBuilder<DispenserWindow, DispenserWindow.Builder> implements DispenserWindow.Builder {
        private Pane upperPane = Pane.empty(new PaneSize(3, 3));
        private @Nullable Pane lowerPane;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperPane = source.upperPane;
            this.lowerPane = source.lowerPane;
        }

        @Override
        @NotNull
        public DispenserWindow.Builder setUpperPane(@NotNull Pane upperPane) {
            this.upperPane = upperPane;
            return this;
        }

        @Override
        @NotNull
        public DispenserWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            return this;
        }

        @Override
        @NotNull
        public DispenserWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected DispenserWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected DispenserWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.upperPane.width() != 3 || this.upperPane.height() != 3)
                throw new IllegalArgumentException("dispenser upper Pane must have size 3x3");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.split(this.upperPane, lowerPane);
            return new DispenserWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
