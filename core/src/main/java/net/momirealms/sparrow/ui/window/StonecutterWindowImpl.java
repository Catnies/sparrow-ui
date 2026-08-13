package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.internal.menu.StonecutterMenuHandle;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

final class StonecutterWindowImpl extends AbstractWindow<StonecutterMenuHandle> implements StonecutterWindow {
    private static final int BUTTONS_START = 38;

    private final int buttonCapacity;
    private volatile int selectedRecipeIndex;
    private volatile int effectiveRecipeCount;

    StonecutterWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            int buttonCapacity,
            int selectedRecipeIndex
    ) {
        super(manager, viewer, layout, settings);
        this.buttonCapacity = buttonCapacity;
        this.selectedRecipeIndex = selectedRecipeIndex;
    }

    @Override
    public int getSelectedRecipeIndex() {
        return this.selectedRecipeIndex;
    }

    @Override
    public void setSelectedRecipeIndex(int index) {
        if (index < -1 || index >= this.buttonCapacity) {
            throw new IndexOutOfBoundsException("stonecutter selected recipe index out of bounds: " + index);
        }
        this.submit(
                () -> {
                    StonecutterMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null && index >= this.effectiveRecipeCount) {
                        throw new IndexOutOfBoundsException("stonecutter selected recipe index out of effective bounds: " + index);
                    }
                    this.selectedRecipeIndex = index;
                    if (menuHandle != null) {
                        menuHandle.setSelectedRecipeIndex(index);
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update Stonecutter Window selected recipe"
        );
    }

    @Override
    @NotNull
    protected StonecutterMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        return factory.stonecutter(this.viewer(), generation);
    }

    @Override
    protected void prepareVirtualContent(@NotNull StonecutterMenuHandle menuHandle, ItemStack @NotNull [] logicalSlots) {
        // 统计实际显示的长度
        int recipeCount = 0;
        for (int windowSlot = logicalSlots.length - 1; windowSlot >= BUTTONS_START; windowSlot--) {
            if (!logicalSlots[windowSlot].isEmpty()) {
                recipeCount = windowSlot - BUTTONS_START + 1;
                break;
            }
        }

        this.effectiveRecipeCount = recipeCount;
        if (this.selectedRecipeIndex >= recipeCount) {
            this.selectedRecipeIndex = -1;
        }
        menuHandle.setRecipeButtons(Arrays.copyOfRange(logicalSlots, BUTTONS_START, BUTTONS_START + recipeCount));
        menuHandle.setSelectedRecipeIndex(this.selectedRecipeIndex);
    }

    @Override
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
        if (input instanceof MenuInput.WindowSpecific.ButtonClick(int containerId, int selectedIndex)) {
            StonecutterMenuHandle menuHandle = this.menuHandle();
            if (menuHandle == null || containerId != menuHandle.containerId()) {
                return;
            }

            if (selectedIndex < 0 || selectedIndex >= this.effectiveRecipeCount) {
                menuHandle.reconcileClientSelection(this.selectedRecipeIndex);
                this.notifyUpdateMenu();
                return;
            }

            this.selectedRecipeIndex = selectedIndex;
            menuHandle.reconcileClientSelection(selectedIndex);
            this.notifyUpdateMenu();
            this.dispatchItemClick(BUTTONS_START + selectedIndex, ClickType.LEFT);
        }
    }

    static final class BuilderImpl extends AbstractWindowBuilder<StonecutterWindow, StonecutterWindow.Builder> implements StonecutterWindow.Builder {
        private Pane upperPane = Pane.empty(new PaneSize(2, 1));
        private @Nullable Pane lowerPane;
        private Pane buttonsPane = Pane.empty(4, 0);
        private int selectedRecipeIndex = -1;

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperPane = source.upperPane;
            this.lowerPane = source.lowerPane;
            this.buttonsPane = source.buttonsPane;
            this.selectedRecipeIndex = source.selectedRecipeIndex;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder setUpperPane(@NotNull Pane upperPane) {
            this.upperPane = Objects.requireNonNull(upperPane, "upperPane");
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder setButtonsPane(@NotNull Pane buttonsPane) {
            this.buttonsPane = Objects.requireNonNull(buttonsPane, "buttonsPane");
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder setSelectedRecipeIndex(int index) {
            this.selectedRecipeIndex = index;
            return this;
        }

        @Override
        @NotNull
        public StonecutterWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected StonecutterWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected StonecutterWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.upperPane.width() != 2 || this.upperPane.height() != 1)
                throw new IllegalArgumentException("stonecutter upper Pane must have size 2x1");
            if (this.buttonsPane.width() != 4)
                throw new IllegalArgumentException("stonecutter buttons Pane must have width 4");
            if (this.selectedRecipeIndex < -1 || this.selectedRecipeIndex >= this.buttonsPane.area())
                throw new IndexOutOfBoundsException("stonecutter selected recipe index out of bounds: " + this.selectedRecipeIndex);

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.upperPane),
                    WindowLayout.Region.lower(lowerPane),
                    WindowLayout.Region.virtual(this.buttonsPane)
            );
            return new StonecutterWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.buttonsPane.area(),
                    this.selectedRecipeIndex
            );
        }
    }
}
