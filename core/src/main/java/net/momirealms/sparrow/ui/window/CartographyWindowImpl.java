package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.CartographyMenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import org.bukkit.entity.Player;
import org.bukkit.map.MapPalette;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

final class CartographyWindowImpl extends AbstractWindow<CartographyMenuHandle> implements CartographyWindow {
    private final byte[] canvas;
    private volatile Set<MapIcon> icons;
    private volatile View view;

    CartographyWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            byte @NotNull [] canvas,
            @NotNull Set<MapIcon> icons,
            @NotNull View view
    ) {
        super(manager, viewer, layout, settings);
        this.canvas = canvas.clone();
        this.icons = icons;
        this.view = view;
    }

    @Override
    public void applyPatch(@NotNull MapPatch patch) {
        MapPatch snapshot = new MapPatch(patch.startX(), patch.startY(), patch.width(), patch.height(), patch.colors());
        this.submit(
                () -> {
                    CartographyWindowImpl.applyToCanvas(this.canvas, snapshot);
                    CartographyMenuHandle menu = this.menuHandle();
                    if (menu != null) {
                        menu.applyPatch(snapshot);
                        this.notifySynchronize();
                    }
                },
                "Failed to apply Cartography Window map patch"
        );
    }

    @Override
    public void setIcons(@NotNull Set<? extends MapIcon> icons) {
        Set<MapIcon> snapshot = CartographyWindowImpl.copyIcons(icons);
        this.submit(
                () -> {
                    this.icons = snapshot;
                    CartographyMenuHandle menu = this.menuHandle();
                    if (menu != null) {
                        menu.setIcons(snapshot);
                        this.notifySynchronize();
                    }
                },
                "Failed to update Cartography Window map icons"
        );
    }

    @Override
    @NotNull
    public Set<MapIcon> getIcons() {
        return this.icons;
    }

    @Override
    public void resetMap() {
        this.submit(
                () -> {
                    Arrays.fill(this.canvas, (byte) 0);
                    this.icons = Set.of();
                    CartographyMenuHandle menu = this.menuHandle();
                    if (menu != null) {
                        menu.resetMap();
                        this.notifySynchronize();
                    }
                },
                "Failed to reset Cartography Window map"
        );
    }

    @Override
    public void setView(@NotNull View view) {
        Objects.requireNonNull(view, "view");
        this.submit(
                () -> {
                    this.view = view;
                    CartographyMenuHandle menu = this.menuHandle();
                    if (menu != null) {
                        menu.setView(view);
                        this.notifySynchronize();
                    }
                },
                "Failed to update Cartography Window view"
        );
    }

    @Override
    @NotNull
    public View getView() {
        return this.view;
    }

    @Override
    @NotNull
    protected CartographyMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        CartographyMenuHandle menu = factory.cartography(this.viewer(), generation);
        menu.setView(this.view);
        menu.setIcons(this.icons);
        menu.applyPatch(new MapPatch(0, 0, MAP_SIZE, MAP_SIZE, this.canvas));
        return menu;
    }

    private static void applyToCanvas(byte[] canvas, MapPatch patch) {
        byte[] colors = patch.colors();
        for (int row = 0; row < patch.height(); row++) {
            System.arraycopy(
                    colors,
                    row * patch.width(),
                    canvas,
                    (patch.startY() + row) * MAP_SIZE + patch.startX(),
                    patch.width()
            );
        }
    }

    private static Set<MapIcon> copyIcons(Set<? extends MapIcon> icons) {
        Objects.requireNonNull(icons, "icons");
        LinkedHashSet<MapIcon> copy = new LinkedHashSet<>(icons.size());
        for (MapIcon icon : icons) {
            copy.add(Objects.requireNonNull(icon, "icons contains null"));
        }
        return Collections.unmodifiableSet(copy);
    }

    /**
     * 制图台 Window Builder 的实现.
     */
    static final class BuilderImpl extends AbstractWindowBuilder<CartographyWindow, CartographyWindow.Builder> implements CartographyWindow.Builder {
        private Gui inputGui = Gui.empty(new GuiSize(1, 2));
        private Gui resultGui = Gui.empty(new GuiSize(1, 1));
        private @Nullable Gui lowerGui;
        private byte[] canvas = new byte[MAP_SIZE * MAP_SIZE];
        private Set<MapIcon> icons = Set.of();
        private View view = View.NORMAL;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.inputGui = source.inputGui;
            this.resultGui = source.resultGui;
            this.lowerGui = source.lowerGui;
            this.canvas = source.canvas.clone();
            this.icons = CartographyWindowImpl.copyIcons(source.icons);
            this.view = source.view;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder setInputGui(@NotNull Gui inputGui) {
            this.inputGui = inputGui;
            return this;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder setResultGui(@NotNull Gui resultGui) {
            this.resultGui = resultGui;
            return this;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
            return this;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder setIcons(@NotNull Set<? extends MapIcon> icons) {
            this.icons = CartographyWindowImpl.copyIcons(icons);
            return this;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder setMap(byte @NotNull [] colors) {
            Objects.requireNonNull(colors, "colors");
            if (colors.length != MAP_SIZE * MAP_SIZE) {
                throw new IllegalArgumentException(
                        "cartography map requires " + (MAP_SIZE * MAP_SIZE) + " colors, got " + colors.length
                );
            }
            this.canvas = colors.clone();
            return this;
        }

        @Override
        @NotNull
        @SuppressWarnings("removal")
        public CartographyWindow.Builder setMap(@NotNull BufferedImage image) {
            Objects.requireNonNull(image, "image");
            if (image.getWidth() != MAP_SIZE || image.getHeight() != MAP_SIZE) {
                throw new IllegalArgumentException("cartography map image must have size 128x128");
            }
            this.canvas = MapPalette.imageToBytes(image);
            return this;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder setView(@NotNull View view) {
            this.view = Objects.requireNonNull(view, "view");
            return this;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected CartographyWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected CartographyWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.inputGui.width() != 1 || this.inputGui.height() != 2)
                throw new IllegalArgumentException("cartography input GUI must have size 1x2");
            if (this.resultGui.width() != 1 || this.resultGui.height() != 1)
                throw new IllegalArgumentException("cartography result GUI must have size 1x1");

            this.lowerGui = this.lowerGui == null ? viewerReferencingInventory(viewer) : this.lowerGui;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.inputGui),
                    WindowLayout.Region.upper(this.resultGui),
                    WindowLayout.Region.lower(this.lowerGui)
            );
            return new CartographyWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.canvas,
                    CartographyWindowImpl.copyIcons(this.icons),
                    this.view
            );
        }
    }
}
