package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class DropperWindowImpl extends AbstractWindow<MenuHandle> implements DropperWindow {

    DropperWindowImpl(
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
        return factory.dropper(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractWindowBuilder<DropperWindow, DropperWindow.Builder> implements DropperWindow.Builder {
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
        public DropperWindow.Builder setUpperPane(@NotNull Pane upperPane) {
            this.upperPane = upperPane;
            return this;
        }

        @Override
        @NotNull
        public DropperWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            return this;
        }

        @Override
        @NotNull
        public DropperWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected DropperWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected DropperWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.upperPane.width() != 3 || this.upperPane.height() != 3)
                throw new IllegalArgumentException("dropper upper Pane must have size 3x3");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.split(this.upperPane, lowerPane);
            return new DropperWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
