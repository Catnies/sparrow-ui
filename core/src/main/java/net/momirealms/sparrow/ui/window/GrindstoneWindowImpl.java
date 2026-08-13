package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class GrindstoneWindowImpl extends AbstractWindow<MenuHandle> implements GrindstoneWindow {

    GrindstoneWindowImpl(
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
        return factory.grindstone(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractWindowBuilder<GrindstoneWindow, GrindstoneWindow.Builder> implements GrindstoneWindow.Builder {
        private Pane inputPane = Pane.empty(new PaneSize(1, 2));
        private Pane resultPane = Pane.empty(new PaneSize(1, 1));
        private @Nullable Pane lowerPane;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.inputPane = source.inputPane;
            this.resultPane = source.resultPane;
            this.lowerPane = source.lowerPane;
        }

        @Override
        @NotNull
        public GrindstoneWindow.Builder setInputPane(@NotNull Pane inputPane) {
            this.inputPane = inputPane;
            return this;
        }

        @Override
        @NotNull
        public GrindstoneWindow.Builder setResultPane(@NotNull Pane resultPane) {
            this.resultPane = resultPane;
            return this;
        }

        @Override
        @NotNull
        public GrindstoneWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            return this;
        }

        @Override
        @NotNull
        public GrindstoneWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected GrindstoneWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected GrindstoneWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.inputPane.width() != 1 || this.inputPane.height() != 2)
                throw new IllegalArgumentException("grindstone input Pane must have size 1x2");
            if (this.resultPane.width() != 1 || this.resultPane.height() != 1)
                throw new IllegalArgumentException("grindstone result Pane must have size 1x1");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.inputPane),
                    WindowLayout.Region.upper(this.resultPane),
                    WindowLayout.Region.lower(lowerPane)
            );
            return new GrindstoneWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
