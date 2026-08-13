package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class SmithingWindowImpl extends AbstractWindow<MenuHandle> implements SmithingWindow {

    SmithingWindowImpl(
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
        return factory.smithing(this.viewer(), generation);
    }

    static final class BuilderImpl extends AbstractWindowBuilder<SmithingWindow, SmithingWindow.Builder> implements SmithingWindow.Builder {
        private Pane upperPane = Pane.empty(new PaneSize(4, 1));
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
        public SmithingWindow.Builder setUpperPane(@NotNull Pane upperPane) {
            this.upperPane = upperPane;
            return this;
        }

        @Override
        @NotNull
        public SmithingWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            return this;
        }

        @Override
        @NotNull
        public SmithingWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected SmithingWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected SmithingWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.upperPane.width() != 4 || this.upperPane.height() != 1)
                throw new IllegalArgumentException("smithing upper Pane must have size 4x1");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.split(this.upperPane, lowerPane);
            return new SmithingWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
