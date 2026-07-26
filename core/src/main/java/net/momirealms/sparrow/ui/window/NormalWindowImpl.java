package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
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
        private Gui upperGui = Gui.empty(new GuiSize(9, 6));
        private @Nullable Gui lowerGui;
        private @Nullable Gui mergedGui;

        BuilderImpl() {
        }

        BuilderImpl(@NotNull Gui mergedGui) {
            this.mergedGui = mergedGui;
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperGui = source.upperGui;
            this.lowerGui = source.lowerGui;
            this.mergedGui = source.mergedGui;
        }

        @NotNull
        @Override
        public NormalWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = upperGui;
            this.mergedGui = null;
            return this;
        }

        @NotNull
        @Override
        public NormalWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
            this.mergedGui = null;
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
            if (this.mergedGui != null) {
                if (this.mergedGui.width() != 9 || this.mergedGui.height() < 5 || this.mergedGui.height() > 10) {
                    throw new IllegalArgumentException("merged GUI must have width 9 and height between 5 and 10");
                }
                layout = WindowLayout.merged(this.mergedGui);
            } else {
                if (this.upperGui.width() != 9 || this.upperGui.height() < 1 || this.upperGui.height() > 6) {
                    throw new IllegalArgumentException("normal upper GUI must have width 9 and height between 1 and 6");
                }
                layout = this.lowerGui == null
                        ? WindowLayout.upper(this.upperGui)
                        : WindowLayout.split(this.upperGui, this.lowerGui);
            }
            return new NormalWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
