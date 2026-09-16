package net.momirealms.sparrow.ui;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.visual.AbstractVisual;
import net.momirealms.sparrow.ui.visual.CursorVisual;
import net.momirealms.sparrow.ui.visual.WindowVisual;
import net.momirealms.sparrow.ui.visual.WindowVisualImpl;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import net.momirealms.sparrow.ui.visual.animation.TitleAnimationDefinition;
import net.momirealms.sparrow.ui.window.Window;
import net.momirealms.sparrow.ui.window.WindowCloseReason;
import net.momirealms.sparrow.ui.window.WindowSession;
import net.momirealms.sparrow.ui.window.click.WindowOutsideClick;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class WindowStub implements Window {

    private static final int VISUAL_SIZE = 64;
    private final Bindings bindings = Bindings.suspended();
    private final WindowVisualImpl windowVisual = new WindowVisualImpl(this.bindings, VISUAL_SIZE);
    private final StubCursorVisual cursorVisual = new StubCursorVisual();
    private final Player viewer;
    private final Pane lowerPane = Pane.empty(9, 4);
    private volatile Component title = Component.empty();
    private Supplier<? extends Component> titleSupplier = Component::empty;
    private volatile boolean closeable = true;
    private volatile boolean offhandFrozen;
    private volatile boolean open;
    private final BitSet frozenSlots = new BitSet();
    private List<Runnable> openHandlers = new ArrayList<>();
    private List<Consumer<WindowCloseReason>> closeHandlers = new ArrayList<>();
    private List<Consumer<WindowOutsideClick>> outsideClickHandlers = new ArrayList<>();
    private volatile boolean backOnPlayerClose;
    private int serverWindowState;
    private int clientWindowState;
    private List<Consumer<Integer>> windowStateChangeHandlers = new ArrayList<>();
    public WindowStub(Player viewer) {
        this.viewer = viewer;
    }

    @Override
    public @NotNull List<Pane> panes() {
        return List.of();
    }

    @Override
    @NotNull
    public Pane lowerPane() {
        return this.lowerPane;
    }

    @Override
    @NotNull
    public Element.PaneLink paneAt(int windowSlot) {
        throw new UnsupportedOperationException("WindowStub has no Pane layout");
    }

    @Override
    @NotNull
    public Element.PaneLink paneAtHotbar(int hotbarSlot) {
        throw new UnsupportedOperationException("WindowStub has no Pane layout");
    }

    @Override
    public int windowSlotAtHotbar(int hotbarSlot) {
        throw new UnsupportedOperationException("WindowStub has no Pane layout");
    }

    @Override
    public void notifyUpdate(int windowSlot) {
    }

    @Override
    @NotNull
    public ItemStack displayedAt(int windowSlot) {
        return ItemStack.empty();
    }

    @Override
    public @Nullable Object rememberedAt(int windowSlot) {
        return null;
    }

    @Override
    public @NotNull Player viewer() {
        return this.viewer;
    }

    @Override
    public @NotNull Component title() {
        return this.title;
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    public boolean isCloseable() {
        return this.closeable;
    }

    @Override
    public @NotNull CompletableFuture<OpenResult> open() {
        if (this.open) {
            return CompletableFuture.completedFuture(OpenResult.ALREADY_OPEN);
        }
        this.open = true;
        this.bindings.resumeAll();
        for (int index = 0; index < this.openHandlers.size(); index++) {
            this.openHandlers.get(index).run();
        }
        return CompletableFuture.completedFuture(OpenResult.OPENED);
    }

    @Override
    public void setTitleSupplier(@NotNull Supplier<? extends Component> titleSupplier) {
        this.titleSupplier = titleSupplier;
        this.updateTitle();
    }

    @Override
    public void setTitle(@NotNull Component title) {
        this.titleSupplier = () -> title;
        this.title = title;
    }

    @Override
    public void updateTitle() {
        this.title = this.titleSupplier.get();
    }

    @NotNull
    @Override
    public AnimationHandle playTitleAnimation(@NotNull TitleAnimationDefinition animationDefinition) {
        throw new UnsupportedOperationException("WindowStub 不播放标题动画");
    }

    @Override
    public void setCloseable(boolean closeable) {
        this.closeable = closeable;
    }

    @Override
    public boolean frozenAt(int windowSlot) {
        return this.frozenSlots.get(windowSlot);
    }

    @Override
    public void frozenAt(int windowSlot, boolean frozen) {
        this.frozenSlots.set(windowSlot, frozen);
    }

    @Override
    public boolean offhandFrozen() {
        return this.offhandFrozen;
    }

    @Override
    public void offhandFrozen(boolean frozen) {
        this.offhandFrozen = frozen;
    }

    @Override
    public @NotNull CompletableFuture<Window> navigate(@NotNull Window next) {
        next.open().join();
        return CompletableFuture.completedFuture(next);
    }

    @Override
    public @Nullable WindowSession session() {
        return null;
    }

    @Override
    public @Nullable Object data() {
        return null;
    }

    @Override
    public @NotNull CompletableFuture<CloseResult> close() {
        if (!this.open) {
            return CompletableFuture.completedFuture(CloseResult.ALREADY_CLOSED);
        }
        this.open = false;
        this.bindings.suspendAll();
        for (int index = 0; index < this.closeHandlers.size(); index++) {
            this.closeHandlers.get(index).accept(WindowCloseReason.PLUGIN);
        }
        return CompletableFuture.completedFuture(CloseResult.CLOSED);
    }

    @Override
    public @NotNull CompletableFuture<Window> back() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<Window> backOrClose() {
        this.close();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setOpenHandlers(@NotNull List<? extends Runnable> openHandlers) {
        this.openHandlers = new ArrayList<>(openHandlers);
    }

    @Override
    public @NotNull List<Runnable> getOpenHandlers() {
        return List.copyOf(this.openHandlers);
    }

    @Override
    public void addOpenHandler(@NotNull Runnable openHandler) {
        this.openHandlers.add(openHandler);
    }

    @Override
    public void removeOpenHandler(@NotNull Runnable openHandler) {
        this.openHandlers.remove(openHandler);
    }

    @Override
    public void setCloseHandlers(
            @NotNull List<? extends Consumer<? super WindowCloseReason>> closeHandlers
    ) {
        this.closeHandlers = new ArrayList<>(HandlerList.copyConsumers(closeHandlers));
    }

    @Override
    public @NotNull List<Consumer<WindowCloseReason>> getCloseHandlers() {
        return List.copyOf(this.closeHandlers);
    }

    @Override
    public void addCloseHandler(@NotNull Consumer<? super WindowCloseReason> closeHandler) {
        this.closeHandlers.add(HandlerList.narrowConsumer(closeHandler));
    }

    @Override
    @NotNull
    public Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super Window> callback) {
        return this.bindings.bind(() -> signal.onDirty(() -> callback.accept(this)));
    }

    @Override
    public void removeCloseHandler(@NotNull Consumer<? super WindowCloseReason> closeHandler) {
        this.closeHandlers.remove(closeHandler);
    }

    @Override
    public void setOutsideClickHandlers(
            @NotNull List<? extends Consumer<? super WindowOutsideClick>> outsideClickHandlers
    ) {
        this.outsideClickHandlers = new ArrayList<>(HandlerList.copyConsumers(outsideClickHandlers));
    }

    @Override
    public @NotNull List<Consumer<WindowOutsideClick>> getOutsideClickHandlers() {
        return List.copyOf(this.outsideClickHandlers);
    }

    @Override
    public void addOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler) {
        this.outsideClickHandlers.add(HandlerList.narrowConsumer(outsideClickHandler));
    }

    @Override
    public void removeOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler) {
        this.outsideClickHandlers.remove(outsideClickHandler);
    }

    @Override
    public boolean backOnPlayerClose() {
        return this.backOnPlayerClose;
    }

    @Override
    public void backOnPlayerClose(boolean backOnPlayerClose) {
        this.backOnPlayerClose = backOnPlayerClose;
    }

    @Override
    public void setWindowState(int windowState) {
        this.serverWindowState = windowState;
        this.clientWindowState = windowState;
        for (int index = 0; index < this.windowStateChangeHandlers.size(); index++) {
            this.windowStateChangeHandlers.get(index).accept(windowState);
        }
    }

    @Override
    public void incrementWindowState() {
        this.setWindowState(this.serverWindowState + 1);
    }

    @Override
    public int serverWindowState() {
        return this.serverWindowState;
    }

    @Override
    public int clientWindowState() {
        return this.clientWindowState;
    }

    @Override
    public void setWindowStateChangeHandlers(@NotNull List<? extends Consumer<? super Integer>> handlers) {
        this.windowStateChangeHandlers = new ArrayList<>(HandlerList.copyConsumers(handlers));
    }

    @Override
    public @NotNull List<Consumer<Integer>> getWindowStateChangeHandlers() {
        return List.copyOf(this.windowStateChangeHandlers);
    }

    @Override
    public void addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        this.windowStateChangeHandlers.add(HandlerList.narrowConsumer(handler));
    }

    @Override
    public void removeWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        this.windowStateChangeHandlers.remove(handler);
    }

    @NotNull
    @Override
    public WindowVisual visual() {
        return this.windowVisual;
    }

    @NotNull
    @Override
    public CursorVisual cursorVisual() {
        return this.cursorVisual;
    }

    @Override
    public void notifyUpdateAll() {
    }

    private final class StubCursorVisual extends AbstractVisual implements CursorVisual {
        private volatile @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider;
        private StubCursorVisual() {
            super(WindowStub.this.bindings);
        }
        @Override
        public void dirty() {
        }
        @Nullable
        @Override
        public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
            return this.visualizerProvider;
        }
        @Override
        public void setVisualizerProvider(
                @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
                @Nullable ImmediateItemProvider placeholder
        ) {
            this.visualizerProvider = visualizerProvider;
        }
    }
}
