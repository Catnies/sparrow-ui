package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
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
                        this.notifyUpdateMenu();
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
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update Cartography Window map icons"
        );
    }

    @NotNull
    @Override
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
                        this.notifyUpdateMenu();
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
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update Cartography Window view"
        );
    }

    @NotNull
    @Override
    public View getView() {
        return this.view;
    }

    @NotNull
    @Override
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

    static final class BuilderImpl extends AbstractWindowBuilder<CartographyWindow, CartographyWindow.Builder> implements CartographyWindow.Builder {
        private Pane inputPane = Pane.empty(new PaneSize(1, 2));
        private Pane resultPane = Pane.empty(new PaneSize(1, 1));
        private @Nullable Pane lowerPane;
        private byte[] canvas = new byte[MAP_SIZE * MAP_SIZE];
        private Set<MapIcon> icons = Set.of();
        private View view = View.NORMAL;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.inputPane = source.inputPane;
            this.resultPane = source.resultPane;
            this.lowerPane = source.lowerPane;
            this.canvas = source.canvas.clone();
            this.icons = CartographyWindowImpl.copyIcons(source.icons);
            this.view = source.view;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder setInputPane(@NotNull Pane inputPane) {
            this.inputPane = inputPane;
            return this;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder setResultPane(@NotNull Pane resultPane) {
            this.resultPane = resultPane;
            return this;
        }

        @Override
        @NotNull
        public CartographyWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
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
            if (this.inputPane.width() != 1 || this.inputPane.height() != 2)
                throw new IllegalArgumentException("cartography input Pane must have size 1x2");
            if (this.resultPane.width() != 1 || this.resultPane.height() != 1)
                throw new IllegalArgumentException("cartography result Pane must have size 1x1");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.inputPane),
                    WindowLayout.Region.upper(this.resultPane),
                    WindowLayout.Region.lower(lowerPane)
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
