package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 漏斗 Window 的实体线程实现.
 */
final class HopperWindowImpl extends AbstractWindow<MenuHandle> implements HopperWindow {

    HopperWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings
    ) {
        super(manager, viewer, layout, settings);
    }

    @Override
    protected @NotNull MenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.createHopper(this.viewer(), generation);
    }

    /**
     * 漏斗 Window Builder 的实现.
     */
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

        @Override
        public @NotNull HopperWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = upperGui;
            return this;
        }

        @Override
        public @NotNull HopperWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
            return this;
        }

        @Override
        public @NotNull HopperWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        protected @NotNull HopperWindow.Builder self() {
            return this;
        }

        @Override
        protected @NotNull HopperWindow createWindow(
                @NotNull Player viewer,
                @NotNull AbstractWindow.Settings settings
        ) {
            if (this.upperGui.width() != 5 || this.upperGui.height() != 1) {
                throw new IllegalArgumentException("hopper upper GUI must have size 5x1");
            }
            WindowLayout layout = this.lowerGui == null
                    ? WindowLayout.playerInventoryBelow(this.upperGui)
                    : WindowLayout.split(this.upperGui, this.lowerGui);
            return new HopperWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
