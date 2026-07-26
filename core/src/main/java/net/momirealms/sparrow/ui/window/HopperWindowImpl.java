package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
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
        private Gui upperGui = Gui.empty(new GuiSize(5, 1));
        private @Nullable Gui lowerGui;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperGui = source.upperGui;
            this.lowerGui = source.lowerGui;
        }

        @NotNull
        @Override
        public HopperWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = upperGui;
            return this;
        }

        @NotNull
        @Override
        public HopperWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
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
            if (this.upperGui.width() != 5 || this.upperGui.height() != 1) {
                throw new IllegalArgumentException("hopper upper GUI must have size 5x1");
            }
            WindowLayout layout = this.lowerGui == null
                    ? WindowLayout.upper(this.upperGui)
                    : WindowLayout.split(this.upperGui, this.lowerGui);
            return new HopperWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
