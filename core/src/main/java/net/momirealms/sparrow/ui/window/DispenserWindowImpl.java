package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
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
        public DispenserWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = upperGui;
            return this;
        }

        @Override
        @NotNull
        public DispenserWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
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
            if (this.upperGui.width() != 3 || this.upperGui.height() != 3)
                throw new IllegalArgumentException("dispenser upper GUI must have size 3x3");

            this.lowerGui = this.lowerGui == null ? viewerReferencingInventory(viewer) : this.lowerGui;
            WindowLayout layout = WindowLayout.split(this.upperGui, this.lowerGui);
            return new DispenserWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
