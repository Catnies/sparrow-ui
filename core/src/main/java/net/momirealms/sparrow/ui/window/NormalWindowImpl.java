package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class NormalWindowImpl extends AbstractWindow<MenuHandle> implements NormalWindow {

    NormalWindowImpl(
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
        return factory.normal(this.viewer(), this.upperSize() / 9, generation);
    }

    static final class BuilderImpl extends AbstractWindowBuilder<NormalWindow, NormalWindow.Builder> implements NormalWindow.Builder {
        private Pane upperPane = Pane.empty(new PaneSize(9, 6));
        private @Nullable Pane lowerPane;
        private @Nullable Pane mergedPane;

        BuilderImpl() {
        }

        BuilderImpl(@NotNull Pane mergedPane) {
            this.mergedPane = mergedPane;
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperPane = source.upperPane;
            this.lowerPane = source.lowerPane;
            this.mergedPane = source.mergedPane;
        }

        @NotNull
        @Override
        public NormalWindow.Builder setUpperPane(@NotNull Pane upperPane) {
            this.upperPane = upperPane;
            this.mergedPane = null;
            return this;
        }

        @NotNull
        @Override
        public NormalWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            this.mergedPane = null;
            return this;
        }

        @NotNull
        @Override
        public NormalWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @NotNull
        @Override
        protected NormalWindow.Builder self() {
            return this;
        }

        @NotNull
        @Override
        protected NormalWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            WindowLayout layout;
            if (this.mergedPane != null) {
                if (this.mergedPane.width() != 9 || this.mergedPane.height() < 5 || this.mergedPane.height() > 10) {
                    throw new IllegalArgumentException("merged Pane must have width 9 and height between 5 and 10");
                }
                layout = WindowLayout.merged(this.mergedPane);
            } else {
                if (this.upperPane.width() != 9 || this.upperPane.height() < 1 || this.upperPane.height() > 6) {
                    throw new IllegalArgumentException("normal upper Pane must have width 9 and height between 1 and 6");
                }
                Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
                layout = WindowLayout.split(this.upperPane, lowerPane);
            }
            return new NormalWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
