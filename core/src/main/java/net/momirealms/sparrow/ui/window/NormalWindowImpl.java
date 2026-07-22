package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 普通箱子 Window 的实体线程实现.
 */
final class NormalWindowImpl extends AbstractWindow<MenuHandle> implements NormalWindow {

    NormalWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings
    ) {
        super(manager, viewer, layout, settings);
    }

    @Override
    protected @NotNull MenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.createNormal(this.viewer(), this.topSlots() / 9, generation);
    }

    /**
     * 普通箱子 Window Builder 的实现.
     */
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

        @Override
        public @NotNull NormalWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = upperGui;
            this.mergedGui = null;
            return this;
        }

        @Override
        public @NotNull NormalWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
            this.mergedGui = null;
            return this;
        }

        @Override
        public @NotNull NormalWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        protected @NotNull NormalWindow.Builder self() {
            return this;
        }

        @Override
        protected @NotNull NormalWindow createWindow(
                @NotNull Player viewer,
                @NotNull AbstractWindow.Settings settings
        ) {
            WindowLayout layout;
            if (this.mergedGui != null) {
                checkMerged(this.mergedGui);
                layout = WindowLayout.merged(this.mergedGui);
            } else {
                checkUpper(this.upperGui);
                layout = this.lowerGui == null
                        ? WindowLayout.playerInventoryBelow(this.upperGui)
                        : WindowLayout.split(this.upperGui, this.lowerGui);
            }
            return new NormalWindowImpl(WindowManager.getInstance(), viewer, layout, settings);
        }

        private static void checkUpper(Gui gui) {
            if (gui.width() != 9 || gui.height() < 1 || gui.height() > 6) {
                throw new IllegalArgumentException("normal upper GUI must have width 9 and height between 1 and 6");
            }
        }

        private static void checkMerged(Gui gui) {
            if (gui.width() != 9 || gui.height() < 5 || gui.height() > 10) {
                throw new IllegalArgumentException("merged GUI must have width 9 and height between 5 and 10");
            }
        }
    }
}
