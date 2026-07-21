package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.ClickEvent;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.util.MiscUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link Window.Builder} 的固定布局实现.
 * Builder 本身可复用, 每次 build 都创建独立的 Window 配置与生命周期实例.
 */
final class WindowBuilder implements Window.Builder {
    private final WindowLayout layout;
    private @Nullable Player viewer;
    private Supplier<? extends Component> titleSupplier = Component::empty;
    private boolean closeable = true;
    private List<Runnable> openHandlers = new ArrayList<>();
    private List<Consumer<InventoryCloseEvent.Reason>> closeHandlers = new ArrayList<>();
    private List<Consumer<ClickEvent>> outsideClickHandlers = new ArrayList<>();
    private Supplier<? extends @Nullable Window> fallbackWindow = () -> null;
    private int windowState;
    private List<Consumer<Integer>> windowStateChangeHandlers = new ArrayList<>();
    private Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer = _ -> null;
    private List<Consumer<? super Window>> modifiers = new ArrayList<>();

    private WindowBuilder(WindowLayout layout) {
        this.layout = layout;
    }

    /**
     * 创建上半部分由 GUI、下半部分由玩家原生物品栏构成的 Builder.
     */
    static @NotNull Window.Builder normal(@NotNull Gui gui) {
        return new WindowBuilder(WindowLayout.normal(gui));
    }

    /**
     * 创建上下区域分别由两个 GUI 管理的 Builder.
     */
    static @NotNull Window.Builder split(@NotNull Gui upperGui, @NotNull Gui lowerGui) {
        return new WindowBuilder(WindowLayout.split(upperGui, lowerGui));
    }

    /**
     * 创建一个 GUI 覆盖整个协议窗口的 Builder.
     */
    static @NotNull Window.Builder merged(@NotNull Gui gui) {
        return new WindowBuilder(WindowLayout.merged(gui));
    }

    @Override
    public @NotNull Window.Builder setViewer(@NotNull Player viewer) {
        this.viewer = viewer;
        return this;
    }

    @Override
    public @NotNull Window.Builder setTitleSupplier(@NotNull Supplier<? extends Component> titleSupplier) {
        this.titleSupplier = titleSupplier;
        return this;
    }

    @Override
    public @NotNull Window.Builder setTitle(@NotNull Component title) {
        this.titleSupplier = () -> title;
        return this;
    }

    @Override
    public @NotNull Window.Builder setCloseable(boolean closeable) {
        this.closeable = closeable;
        return this;
    }

    @Override
    public @NotNull Window.Builder setOpenHandlers(@NotNull List<? extends Runnable> openHandlers) {
        this.openHandlers = new ArrayList<>(openHandlers);
        return this;
    }

    @Override
    public @NotNull Window.Builder addOpenHandler(@NotNull Runnable openHandler) {
        this.openHandlers.add(openHandler);
        return this;
    }

    @Override
    public @NotNull Window.Builder setCloseHandlers(@NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> closeHandlers) {
        this.closeHandlers = copyConsumers(closeHandlers);
        return this;
    }

    @Override
    public @NotNull Window.Builder addCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler) {
        this.closeHandlers.add(MiscUtils.narrowConsumer(closeHandler));
        return this;
    }

    @Override
    public @NotNull Window.Builder setOutsideClickHandlers(
            @NotNull List<? extends Consumer<? super ClickEvent>> outsideClickHandlers
    ) {
        this.outsideClickHandlers = copyConsumers(outsideClickHandlers);
        return this;
    }

    @Override
    public @NotNull Window.Builder addOutsideClickHandler(
            @NotNull Consumer<? super ClickEvent> outsideClickHandler
    ) {
        this.outsideClickHandlers.add(MiscUtils.narrowConsumer(outsideClickHandler));
        return this;
    }

    @Override
    public @NotNull Window.Builder setFallbackWindow(
            @NotNull Supplier<? extends @Nullable Window> fallbackWindow
    ) {
        this.fallbackWindow = fallbackWindow;
        return this;
    }

    @Override
    public @NotNull Window.Builder setWindowState(int windowState) {
        this.windowState = windowState;
        return this;
    }

    @Override
    public @NotNull Window.Builder setWindowStateChangeHandlers(
            @NotNull List<? extends Consumer<? super Integer>> handlers
    ) {
        this.windowStateChangeHandlers = copyConsumers(handlers);
        return this;
    }

    @Override
    public @NotNull Window.Builder addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        this.windowStateChangeHandlers.add(MiscUtils.narrowConsumer(handler));
        return this;
    }

    @Override
    public @NotNull Window.Builder setCursorVisualizer(
            @NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer
    ) {
        this.cursorVisualizer = cursorVisualizer;
        return this;
    }

    @Override
    public @NotNull Window.Builder setModifiers(@NotNull List<? extends Consumer<? super Window>> modifiers) {
        this.modifiers = new ArrayList<>(modifiers);
        return this;
    }

    @Override
    public @NotNull Window.Builder addModifier(@NotNull Consumer<? super Window> modifier) {
        this.modifiers.add(modifier);
        return this;
    }

    @Override
    public @NotNull Window.Builder clone() {
        WindowBuilder clone = new WindowBuilder(this.layout);
        clone.viewer = this.viewer;
        clone.titleSupplier = this.titleSupplier;
        clone.closeable = this.closeable;
        clone.openHandlers = new ArrayList<>(this.openHandlers);
        clone.closeHandlers = new ArrayList<>(this.closeHandlers);
        clone.outsideClickHandlers = new ArrayList<>(this.outsideClickHandlers);
        clone.fallbackWindow = this.fallbackWindow;
        clone.windowState = this.windowState;
        clone.windowStateChangeHandlers = new ArrayList<>(this.windowStateChangeHandlers);
        clone.cursorVisualizer = this.cursorVisualizer;
        clone.modifiers = new ArrayList<>(this.modifiers);
        return clone;
    }

    @Override
    public @NotNull Window build() {
        if (this.viewer == null) {
            throw new IllegalStateException("viewer has not been set");
        }
        return this.build(this.viewer);
    }

    @Override
    public @NotNull Window build(@NotNull Player viewer) {
        Window window = WindowManager.getInstance().create(
                viewer,
                this.layout,
                this.titleSupplier,
                this.closeable,
                List.copyOf(this.openHandlers),
                List.copyOf(this.closeHandlers),
                List.copyOf(this.outsideClickHandlers),
                this.fallbackWindow,
                this.windowState,
                List.copyOf(this.windowStateChangeHandlers),
                this.cursorVisualizer
        );
        for (int index = 0; index < this.modifiers.size(); index++) {
            this.modifiers.get(index).accept(window);
        }
        return window;
    }

    /**
     * 保留 Consumer 对象身份地收窄逆变泛型, 使后续 remove 能按原对象匹配.
     */
    private static <T> List<Consumer<T>> copyConsumers(List<? extends Consumer<? super T>> consumers) {
        ArrayList<Consumer<T>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(MiscUtils.narrowConsumer(consumers.get(index)));
        }
        return copy;
    }
}
