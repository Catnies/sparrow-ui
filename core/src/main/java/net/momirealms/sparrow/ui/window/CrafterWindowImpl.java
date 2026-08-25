package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.window.handle.CrafterMenuHandle;
import net.momirealms.sparrow.ui.window.handle.MenuFactory;
import net.momirealms.sparrow.ui.window.handle.MenuInput;
import net.momirealms.sparrow.ui.util.HandlerList;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

final class CrafterWindowImpl extends AbstractWindow<CrafterMenuHandle> implements CrafterWindow {
    private static final int CRAFTING_SLOTS = 9;

    private final HandlerList<BiConsumer<Integer, Boolean>> slotToggleHandlers;
    private volatile int disabledMask;

    CrafterWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            int disabledMask,
            @NotNull List<BiConsumer<Integer, Boolean>> slotToggleHandlers
    ) {
        super(manager, viewer, layout, settings);
        this.disabledMask = disabledMask;
        this.slotToggleHandlers = new HandlerList<>(slotToggleHandlers);
    }

    @Override
    public void setSlotDisabled(int slot, boolean disabled) {
        if (slot < 0 || slot >= CRAFTING_SLOTS) {
            throw new IndexOutOfBoundsException("crafter slot out of bounds: " + slot);
        }
        this.submit(
                () -> {
                    this.disabledMask = CrafterWindowImpl.withSlotState(this.disabledMask, slot, disabled);
                    CrafterMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setSlotDisabled(slot, disabled);
                        this.notifyUpdateMenu();
                    }
                },
                "Failed to update Crafter Window slot state"
        );
    }

    @Override
    public boolean isSlotDisabled(int slot) {
        if (slot < 0 || slot >= CRAFTING_SLOTS) {
            throw new IndexOutOfBoundsException("crafter slot out of bounds: " + slot);
        }
        return (this.disabledMask & 1 << slot) != 0;
    }

    @Override
    public void setSlotToggleHandlers(@NotNull List<? extends BiConsumer<? super Integer, ? super Boolean>> handlers) {
        List<BiConsumer<Integer, Boolean>> copy = HandlerList.copyBiConsumers(handlers);
        this.submit(
                () -> this.slotToggleHandlers.set(copy),
                "Failed to replace Crafter Window slot toggle handlers"
        );
    }

    @Override
    @NotNull
    public List<BiConsumer<Integer, Boolean>> getSlotToggleHandlers() {
        return this.slotToggleHandlers.snapshot();
    }

    @Override
    public void addSlotToggleHandler(@NotNull BiConsumer<? super Integer, ? super Boolean> handler) {
        BiConsumer<Integer, Boolean> copied = HandlerList.narrowBiConsumer(handler);
        this.submit(
                () -> this.slotToggleHandlers.append(copied),
                "Failed to add Crafter Window slot toggle handler"
        );
    }

    @Override
    public void removeSlotToggleHandler(@NotNull BiConsumer<? super Integer, ? super Boolean> handler) {
        this.submit(
                () -> this.slotToggleHandlers.remove(HandlerList.narrowBiConsumer(handler)),
                "Failed to remove Crafter Window slot toggle handler"
        );
    }

    @Override
    @NotNull
    protected CrafterMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        CrafterMenuHandle menuHandle = factory.crafter(this.viewer(), generation);
        for (int slot = 0; slot < CRAFTING_SLOTS; slot++) {
            menuHandle.setSlotDisabled(slot, this.isSlotDisabled(slot));
        }
        return menuHandle;
    }

    @Override
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
        if (input instanceof MenuInput.WindowSpecific.CrafterSlotState state) {
            this.handleSlotState(state);
        }
    }

    private void handleSlotState(MenuInput.WindowSpecific.CrafterSlotState state) {
        CrafterMenuHandle menuHandle = this.menuHandle();
        if (menuHandle == null || state.containerId() != menuHandle.containerId()) {
            return;
        }
        if (state.slot() < 0 || state.slot() >= CRAFTING_SLOTS) {
            this.notifyUpdateMenu();
            return;
        }

        boolean disabled = !state.enabled();
        this.disabledMask = CrafterWindowImpl.withSlotState(this.disabledMask, state.slot(), disabled);
        menuHandle.setSlotDisabled(state.slot(), disabled);
        this.notifyUpdateMenu();

        this.slotToggleHandlers.forEachIsolated(
                handler -> handler.accept(state.slot(), disabled),
                "Failed to handle Crafter Window slot toggle",
                this::report
        );
    }

    private static int withSlotState(int mask, int slot, boolean disabled) {
        return disabled ? mask | 1 << slot : mask & ~(1 << slot);
    }

    static final class BuilderImpl extends AbstractWindowBuilder<CrafterWindow, CrafterWindow.Builder> implements CrafterWindow.Builder {
        private Pane craftingPane = Pane.empty(new PaneSize(3, 3));
        private Pane resultPane = Pane.empty(new PaneSize(1, 1));
        private @Nullable Pane lowerPane;
        private int disabledMask;
        private List<BiConsumer<Integer, Boolean>> slotToggleHandlers = new ArrayList<>();

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.craftingPane = source.craftingPane;
            this.resultPane = source.resultPane;
            this.lowerPane = source.lowerPane;
            this.disabledMask = source.disabledMask;
            this.slotToggleHandlers = new ArrayList<>(source.slotToggleHandlers);
        }

        @Override
        @NotNull
        public CrafterWindow.Builder setCraftingPane(@NotNull Pane craftingPane) {
            this.craftingPane = craftingPane;
            return this;
        }

        @Override
        @NotNull
        public CrafterWindow.Builder setResultPane(@NotNull Pane resultPane) {
            this.resultPane = resultPane;
            return this;
        }

        @Override
        @NotNull
        public CrafterWindow.Builder setLowerPane(@Nullable Pane lowerPane) {
            this.lowerPane = lowerPane;
            return this;
        }

        @Override
        @NotNull
        public CrafterWindow.Builder setSlotDisabled(int slot, boolean disabled) {
            if (slot < 0 || slot >= CRAFTING_SLOTS) {
                throw new IndexOutOfBoundsException("crafter slot out of bounds: " + slot);
            }
            this.disabledMask = CrafterWindowImpl.withSlotState(this.disabledMask, slot, disabled);
            return this;
        }

        @Override
        @NotNull
        public CrafterWindow.Builder setDisabledSlots(boolean @NotNull ... disabledSlots) {
            if (disabledSlots.length != CRAFTING_SLOTS) {
                throw new IllegalArgumentException("crafter requires exactly nine disabled slot states");
            }
            this.disabledMask = 0;
            for (int slot = 0; slot < disabledSlots.length; slot++) {
                this.disabledMask = CrafterWindowImpl.withSlotState(this.disabledMask, slot, disabledSlots[slot]);
            }
            return this;
        }

        @Override
        @NotNull
        public CrafterWindow.Builder setSlotToggleHandlers(
                @NotNull List<? extends BiConsumer<? super Integer, ? super Boolean>> handlers
        ) {
            this.slotToggleHandlers = new ArrayList<>(HandlerList.copyBiConsumers(handlers));
            return this;
        }

        @Override
        @NotNull
        public CrafterWindow.Builder addSlotToggleHandler(
                @NotNull BiConsumer<? super Integer, ? super Boolean> handler
        ) {
            this.slotToggleHandlers.add(HandlerList.narrowBiConsumer(handler));
            return this;
        }

        @Override
        @NotNull
        public CrafterWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected CrafterWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected CrafterWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.craftingPane.width() != 3 || this.craftingPane.height() != 3)
                throw new IllegalArgumentException("crafter crafting Pane must have size 3x3");
            if (this.resultPane.width() != 1 || this.resultPane.height() != 1)
                throw new IllegalArgumentException("crafter result Pane must have size 1x1");

            Pane lowerPane = this.lowerPane == null ? viewerReferencingInventory(viewer) : this.lowerPane;
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.craftingPane),
                    WindowLayout.Region.lower(lowerPane),
                    WindowLayout.Region.upper(this.resultPane)
            );
            return new CrafterWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.disabledMask,
                    List.copyOf(this.slotToggleHandlers)
            );
        }
    }
}
