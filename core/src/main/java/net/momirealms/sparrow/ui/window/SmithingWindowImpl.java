package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
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
        private Gui upperGui = Gui.empty(new GuiSize(4, 1));
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
        public SmithingWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = upperGui;
            return this;
        }

        @Override
        @NotNull
        public SmithingWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
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
            if (this.upperGui.width() != 4 || this.upperGui.height() != 1) {
                throw new IllegalArgumentException("smithing upper GUI must have size 4x1");
            }
            WindowLayout layout = this.lowerGui == null
                    ? WindowLayout.upper(this.upperGui)
                    : WindowLayout.split(this.upperGui, this.lowerGui);
            return new SmithingWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }
    }
}
