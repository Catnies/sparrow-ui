package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.window.click.WindowOutsideClick;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.util.HandlerList;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 各类 Window Builder 共用的生命周期配置实现.
 *
 * <p>具体 Builder 只负责自身布局约束与菜单类型, 本类负责复制公共配置并保证 Builder 可复用.</p>
 *
 * @param <W> Builder 创建的 Window 类型
 * @param <B> 具体 Builder 类型
 */
abstract class AbstractWindowBuilder<W extends Window, B extends Window.Builder<W, B>> implements Window.Builder<W, B> {
    private @Nullable Player viewer;
    private Supplier<? extends Component> titleSupplier = Component::empty;
    private boolean closeable = true;
    private List<Runnable> openHandlers = new ArrayList<>();
    private List<Consumer<InventoryCloseEvent.Reason>> closeHandlers = new ArrayList<>();
    private List<BiConsumer<W, WindowOutsideClick>> outsideClickHandlers = new ArrayList<>(); // 每次 build 后绑定到当次 W
    private Supplier<? extends @Nullable Window> fallbackWindow = () -> null;
    private int windowState;
    private List<Consumer<Integer>> windowStateChangeHandlers = new ArrayList<>();
    private Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer = ignoredCursor -> null;
    private List<Consumer<? super W>> modifiers = new ArrayList<>();

    AbstractWindowBuilder() {
    }

    AbstractWindowBuilder(@NotNull AbstractWindowBuilder<W, B> source) {
        this.viewer = source.viewer;
        this.titleSupplier = source.titleSupplier;
        this.closeable = source.closeable;
        this.openHandlers = new ArrayList<>(source.openHandlers);
        this.closeHandlers = new ArrayList<>(source.closeHandlers);
        this.outsideClickHandlers = new ArrayList<>(source.outsideClickHandlers);
        this.fallbackWindow = source.fallbackWindow;
        this.windowState = source.windowState;
        this.windowStateChangeHandlers = new ArrayList<>(source.windowStateChangeHandlers);
        this.cursorVisualizer = source.cursorVisualizer;
        this.modifiers = new ArrayList<>(source.modifiers);
    }

    /**
     * 返回具体 Builder 自身, 用于公共链式方法保持精确类型.
     *
     * @return 具体 Builder
     */
    protected abstract @NotNull B self();

    @Override
    public abstract @NotNull B clone();

    /**
     * 为指定玩家创建具体 Window.
     *
     * @param viewer 查看者
     * @param settings 公共 Window 设置快照
     * @return 新的具体 Window
     */
    protected abstract @NotNull W createWindow(
            @NotNull Player viewer,
            @NotNull AbstractWindow.Settings settings
    );

    @Override
    public final @NotNull B setViewer(@NotNull Player viewer) {
        this.viewer = viewer;
        return this.self();
    }

    @Override
    public final @NotNull B setTitleSupplier(@NotNull Supplier<? extends Component> titleSupplier) {
        this.titleSupplier = titleSupplier;
        return this.self();
    }

    @Override
    public final @NotNull B setTitle(@NotNull Component title) {
        this.titleSupplier = () -> title;
        return this.self();
    }

    @Override
    public final @NotNull B setCloseable(boolean closeable) {
        this.closeable = closeable;
        return this.self();
    }

    @Override
    public final @NotNull B setOpenHandlers(@NotNull List<? extends Runnable> openHandlers) {
        this.openHandlers = new ArrayList<>(openHandlers);
        return this.self();
    }

    @Override
    public final @NotNull B addOpenHandler(@NotNull Runnable openHandler) {
        this.openHandlers.add(openHandler);
        return this.self();
    }

    @Override
    public final @NotNull B setCloseHandlers(
            @NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> closeHandlers
    ) {
        this.closeHandlers = new ArrayList<>(HandlerList.copyConsumers(closeHandlers));
        return this.self();
    }

    @Override
    public final @NotNull B addCloseHandler(
            @NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler
    ) {
        this.closeHandlers.add(HandlerList.narrowConsumer(closeHandler));
        return this.self();
    }

    @Override
    @NotNull
    public final B setOutsideClickHandlers(
            @NotNull List<? extends BiConsumer<? super W, ? super WindowOutsideClick>> outsideClickHandlers
    ) {
        this.outsideClickHandlers = new ArrayList<>(HandlerList.copyBiConsumers(outsideClickHandlers));
        return this.self();
    }

    @Override
    @NotNull
    public final B addOutsideClickHandler(
            @NotNull BiConsumer<? super W, ? super WindowOutsideClick> outsideClickHandler
    ) {
        this.outsideClickHandlers.add(HandlerList.narrowBiConsumer(outsideClickHandler));
        return this.self();
    }

    @Override
    @NotNull
    public final B addOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler) {
        return this.addOutsideClickHandler((ignoredWindow, click) -> outsideClickHandler.accept(click));
    }

    @Override
    public final @NotNull B setFallbackWindow(@NotNull Supplier<? extends @Nullable Window> fallbackWindow) {
        this.fallbackWindow = fallbackWindow;
        return this.self();
    }

    @Override
    public final @NotNull B setWindowState(int windowState) {
        this.windowState = windowState;
        return this.self();
    }

    @Override
    public final @NotNull B setWindowStateChangeHandlers(
            @NotNull List<? extends Consumer<? super Integer>> handlers
    ) {
        this.windowStateChangeHandlers = new ArrayList<>(HandlerList.copyConsumers(handlers));
        return this.self();
    }

    @Override
    public final @NotNull B addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        this.windowStateChangeHandlers.add(HandlerList.narrowConsumer(handler));
        return this.self();
    }

    @Override
    public final @NotNull B setCursorVisualizer(
            @NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer
    ) {
        this.cursorVisualizer = cursorVisualizer;
        return this.self();
    }

    @Override
    public final @NotNull B setModifiers(@NotNull List<? extends Consumer<? super W>> modifiers) {
        this.modifiers = new ArrayList<>(modifiers);
        return this.self();
    }

    @Override
    public final @NotNull B addModifier(@NotNull Consumer<? super W> modifier) {
        this.modifiers.add(modifier);
        return this.self();
    }

    @Override
    public final @NotNull W build() {
        if (this.viewer == null) {
            throw new IllegalStateException("viewer has not been set");
        }
        return this.build(this.viewer);
    }

    @Override
    public final @NotNull W build(@NotNull Player viewer) {
        // 构造期间先让处理器持有本次 build 的引用容器, Window 完成创建后再发布精确的 W
        AtomicReference<W> windowReference = new AtomicReference<>();
        W window = this.createWindow(viewer, this.settings(windowReference));
        windowReference.set(window);
        for (int index = 0; index < this.modifiers.size(); index++) {
            this.modifiers.get(index).accept(window);
        }
        return window;
    }


    /**
     * 根据本次 Window 玩家创建引用该玩家背包的 ReferencingInventory.
     *
     * @param viewer 查看者
     * @return 本次 Window 使用的 9x4 Pane
     */
    @NotNull
    protected static Pane viewerReferencingInventory(@NotNull Player viewer) {
        ReferencingInventory inventory = ReferencingInventory.fromPlayerStorageContents(viewer.getInventory());
        inventory.operationPriority(OperationCategory.ADD, Integer.MAX_VALUE);
        inventory.operationPriority(OperationCategory.COLLECT, Integer.MIN_VALUE);
        Pane pane = Pane.empty(9, 4);
        for (int slot = 0; slot < inventory.size(); slot++) {
            pane.setElement(slot, Element.inventory(inventory, slot));
        }
        return pane;
    }

    /**
     * 复制本次 build 使用的公共设置.
     *
     * @param windowReference Window 创建后写入的本次 build 引用
     * @return 独立的不可变设置快照
     */
    private AbstractWindow.Settings settings(@NotNull AtomicReference<W> windowReference) {
        List<Consumer<WindowOutsideClick>> boundOutsideClickHandlers = new ArrayList<>(this.outsideClickHandlers.size());
        for (int index = 0; index < this.outsideClickHandlers.size(); index++) {
            BiConsumer<W, WindowOutsideClick> handler = this.outsideClickHandlers.get(index);
            boundOutsideClickHandlers.add(click -> handler.accept(windowReference.get(), click));
        }
        return new AbstractWindow.Settings(
                this.titleSupplier,
                this.closeable,
                List.copyOf(this.openHandlers),
                List.copyOf(this.closeHandlers),
                List.copyOf(boundOutsideClickHandlers),
                this.fallbackWindow,
                this.windowState,
                List.copyOf(this.windowStateChangeHandlers),
                this.cursorVisualizer
        );
    }
}
