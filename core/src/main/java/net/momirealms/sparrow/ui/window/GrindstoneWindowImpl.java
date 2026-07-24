package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
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
        private Gui inputGui = Gui.empty(new GuiSize(1, 2));
        private Gui resultGui = Gui.empty(new GuiSize(1, 1));
        private @Nullable Gui lowerGui;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.inputGui = source.inputGui;
            this.resultGui = source.resultGui;
            this.lowerGui = source.lowerGui;
        }

        @Override
        @NotNull
        public GrindstoneWindow.Builder setInputGui(@NotNull Gui inputGui) {
            this.inputGui = inputGui;
            return this;
        }

        @Override
        @NotNull
        public GrindstoneWindow.Builder setResultGui(@NotNull Gui resultGui) {
            this.resultGui = resultGui;
            return this;
        }

        @Override
        @NotNull
        public GrindstoneWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
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
            if (this.inputGui.width() != 1 || this.inputGui.height() != 2) {
                throw new IllegalArgumentException("grindstone input GUI must have size 1x2");
            }
            if (this.resultGui.width() != 1 || this.resultGui.height() != 1) {
                throw new IllegalArgumentException("grindstone result GUI must have size 1x1");
            }
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.inputGui),
                    WindowLayout.Region.upper(this.resultGui),
                    WindowLayout.Region.lower(this.lowerGui)
            );
            return new GrindstoneWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
