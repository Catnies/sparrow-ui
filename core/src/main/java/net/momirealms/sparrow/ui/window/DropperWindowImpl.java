package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
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
        private Gui upperGui = Gui.empty(new GuiSize(3, 3));
        private @Nullable Gui lowerGui;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperGui = source.upperGui;
            this.lowerGui = source.lowerGui;
        }

        @Override
        @NotNull
        public DropperWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = upperGui;
            return this;
        }

        @Override
        @NotNull
        public DropperWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
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
            if (this.upperGui.width() != 3 || this.upperGui.height() != 3)
                throw new IllegalArgumentException("dropper upper GUI must have size 3x3");

            this.lowerGui = this.lowerGui == null ? viewerReferencingInventory(viewer) : this.lowerGui;
            WindowLayout layout = WindowLayout.split(this.upperGui, this.lowerGui);
            return new DropperWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
