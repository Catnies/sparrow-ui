package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.menu.AnvilMenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.util.MiscUtils;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 铁砧 Window 的实体线程实现.
 */
final class AnvilWindowImpl extends AbstractWindow<AnvilMenuHandle> implements AnvilWindow {
    private final HandlerList<Consumer<String>> renameHandlers;
    private volatile String renameText = "";
    private volatile int enchantmentCost;
    private volatile boolean textFieldAlwaysEnabled;
    private volatile boolean resultAlwaysValid;

    AnvilWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            int enchantmentCost,
            boolean textFieldAlwaysEnabled,
            boolean resultAlwaysValid,
            @NotNull List<Consumer<String>> renameHandlers
    ) {
        super(manager, viewer, layout, settings);
        this.enchantmentCost = enchantmentCost;
        this.textFieldAlwaysEnabled = textFieldAlwaysEnabled;
        this.resultAlwaysValid = resultAlwaysValid;
        this.renameHandlers = new HandlerList<>(renameHandlers);
    }

    @Override
    public @NotNull String getRenameText() {
        return this.renameText;
    }

    @Override
    public int getEnchantmentCost() {
        return this.enchantmentCost;
    }

    @Override
    public void setEnchantmentCost(int enchantmentCost) {
        this.submit(
                () -> {
                    this.enchantmentCost = enchantmentCost;
                    AnvilMenuHandle menu = this.menuHandle();
                    if (menu != null) {
                        menu.setEnchantmentCost(enchantmentCost);
                        this.notifySynchronize();
                    }
                },
                "Failed to update Anvil Window enchantment cost"
        );
    }

    @Override
    public boolean isTextFieldAlwaysEnabled() {
        return this.textFieldAlwaysEnabled;
    }

    @Override
    public void setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled) {
        this.submit(
                () -> {
                    this.textFieldAlwaysEnabled = textFieldAlwaysEnabled;
                    AnvilMenuHandle menu = this.menuHandle();
                    if (menu != null) {
                        menu.setTextFieldAlwaysEnabled(textFieldAlwaysEnabled);
                        this.notifyUpdate(0);
                        this.notifySynchronize();
                    }
                },
                "Failed to update Anvil Window text field state"
        );
    }

    @Override
    public boolean isResultAlwaysValid() {
        return this.resultAlwaysValid;
    }

    @Override
    public void setResultAlwaysValid(boolean resultAlwaysValid) {
        this.submit(
                () -> {
                    this.resultAlwaysValid = resultAlwaysValid;
                    AnvilMenuHandle menu = this.menuHandle();
                    if (menu != null) {
                        menu.setResultAlwaysValid(resultAlwaysValid);
                        this.notifyUpdate(2);
                        this.notifySynchronize();
                    }
                },
                "Failed to update Anvil Window result state"
        );
    }

    @Override
    public void setRenameHandlers(@NotNull List<? extends Consumer<? super String>> handlers) {
        List<Consumer<String>> copy = MiscUtils.copyConsumers(handlers);
        this.submit(() -> this.renameHandlers.set(copy), "Failed to replace Anvil Window rename handlers");
    }

    @Override
    public @NotNull List<Consumer<String>> getRenameHandlers() {
        return this.renameHandlers.snapshot();
    }

    @Override
    public void addRenameHandler(@NotNull Consumer<? super String> handler) {
        Consumer<String> copied = MiscUtils.narrowConsumer(handler);
        this.submit(
                () -> this.renameHandlers.append(copied),
                "Failed to add Anvil Window rename handler"
        );
    }

    @Override
    public void removeRenameHandler(@NotNull Consumer<? super String> handler) {
        this.submit(
                () -> this.renameHandlers.remove(MiscUtils.narrowConsumer(handler)),
                "Failed to remove Anvil Window rename handler"
        );
    }

    @Override
    protected @NotNull AnvilMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        AnvilMenuHandle menuHandle = factory.anvil(this.viewer(), generation);
        menuHandle.setEnchantmentCost(this.enchantmentCost);
        menuHandle.setTextFieldAlwaysEnabled(this.textFieldAlwaysEnabled);
        menuHandle.setResultAlwaysValid(this.resultAlwaysValid);
        this.renameText = "";
        return menuHandle;
    }

    @Override
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
        if (input instanceof MenuInput.WindowSpecific.Rename(String text)) {
            this.handleRename(text);
        }
    }

    private void handleRename(@NotNull String text) {
        this.renameText = text;
        AnvilMenuHandle menu = this.menuHandle();
        if (menu != null) {
            menu.handleRename(text);
        }
        this.notifyUpdate(2);
        this.notifySynchronize();
        this.renameHandlers.forEachIsolated(
                handler -> handler.accept(text),
                "Failed to handle Anvil Window rename",
                this::report
        );
    }

    /**
     * 铁砧 Window Builder 的实现.
     */
    static final class BuilderImpl extends AbstractWindowBuilder<AnvilWindow, AnvilWindow.Builder>
            implements AnvilWindow.Builder {
        private Gui upperGui = Gui.empty(new GuiSize(3, 1));
        private @Nullable Gui lowerGui;
        private int enchantmentCost;
        private boolean textFieldAlwaysEnabled = true;
        private boolean resultAlwaysValid;
        private List<Consumer<String>> renameHandlers = new ArrayList<>();

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperGui = source.upperGui;
            this.lowerGui = source.lowerGui;
            this.enchantmentCost = source.enchantmentCost;
            this.textFieldAlwaysEnabled = source.textFieldAlwaysEnabled;
            this.resultAlwaysValid = source.resultAlwaysValid;
            this.renameHandlers = new ArrayList<>(source.renameHandlers);
        }

        @Override
        public @NotNull AnvilWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = upperGui;
            return this;
        }

        @Override
        public @NotNull AnvilWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
            return this;
        }

        @Override
        public @NotNull AnvilWindow.Builder setEnchantmentCost(int enchantmentCost) {
            this.enchantmentCost = enchantmentCost;
            return this;
        }

        @Override
        public @NotNull AnvilWindow.Builder setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled) {
            this.textFieldAlwaysEnabled = textFieldAlwaysEnabled;
            return this;
        }

        @Override
        public @NotNull AnvilWindow.Builder setResultAlwaysValid(boolean resultAlwaysValid) {
            this.resultAlwaysValid = resultAlwaysValid;
            return this;
        }

        @Override
        public @NotNull AnvilWindow.Builder setRenameHandlers(
                @NotNull List<? extends Consumer<? super String>> handlers
        ) {
            this.renameHandlers = new ArrayList<>(MiscUtils.copyConsumers(handlers));
            return this;
        }

        @Override
        public @NotNull AnvilWindow.Builder addRenameHandler(@NotNull Consumer<? super String> handler) {
            this.renameHandlers.add(MiscUtils.narrowConsumer(handler));
            return this;
        }

        @Override
        public @NotNull AnvilWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        protected @NotNull AnvilWindow.Builder self() {
            return this;
        }

        @Override
        protected @NotNull AnvilWindow createWindow(
                @NotNull Player viewer,
                @NotNull AbstractWindow.Settings settings
        ) {
            if (this.upperGui.width() != 3 || this.upperGui.height() != 1) {
                throw new IllegalArgumentException("anvil upper GUI must have size 3x1");
            }
            WindowLayout layout = this.lowerGui == null
                    ? WindowLayout.upper(this.upperGui)
                    : WindowLayout.split(this.upperGui, this.lowerGui);
            return new AnvilWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.enchantmentCost,
                    this.textFieldAlwaysEnabled,
                    this.resultAlwaysValid,
                    List.copyOf(this.renameHandlers)
            );
        }
    }
}
