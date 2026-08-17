package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.HandlerList;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 会话链的持有者与导航编排者.
 */
final class WindowSessionImpl implements WindowSession {
    private final WindowManager manager;
    private final Player viewer;
    private final HandlerList<Consumer<InventoryCloseEvent.Reason>> endHandlers;
    private final SignalBindings signalBindings = new SignalBindings(); // 本会话持有的 Signal 绑定

    private final List<Window> chain = new ArrayList<>(); // 链底到链顶, 只在玩家实体线程修改
    private volatile List<Window> chainSnapshot = List.of(); // 最近一次已应用的链快照
    private volatile boolean active = true;

    WindowSessionImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull List<Consumer<InventoryCloseEvent.Reason>> endHandlers
    ) {
        this.manager = manager;
        this.viewer = viewer;
        this.endHandlers = new HandlerList<>(endHandlers);
    }

    @NotNull
    @Override
    public CompletableFuture<NavigationResult> open(@NotNull Window window) {
        if (window.viewer() != this.viewer) {
            throw new IllegalArgumentException("window belongs to another viewer than the session");
        }
        AbstractWindow<?> target = (AbstractWindow<?>) window;
        return this.manager.submit(
                this.viewer,
                () -> this.openNow(target),
                () -> NavigationResult.VIEWER_UNAVAILABLE
        );
    }

    /**
     * 在玩家实体线程打开 Window 并把它记为新链顶, 打开失败时不留下任何链变更.
     *
     * @param window 要打开的 Window
     * @return 导航结果
     */
    NavigationResult openNow(@NotNull AbstractWindow<?> window) {
        if (!this.active) {
            return NavigationResult.SESSION_ENDED;
        }
        if (this.manager.openInSession(window, this) == Window.OpenResult.VIEWER_UNAVAILABLE) {
            return NavigationResult.VIEWER_UNAVAILABLE;
        }

        this.advanceTo(window);
        this.manager.publishSession(this);
        return NavigationResult.OPENED;
    }

    @NotNull
    @Override
    public CompletableFuture<NavigationResult> back() {
        return this.manager.submit(
                this.viewer,
                this::backNow,
                () -> NavigationResult.VIEWER_UNAVAILABLE
        );
    }

    /**
     * 在玩家实体线程返回上一层, 来源 Window 以原实例重新打开.
     *
     * @return 导航结果
     */
    NavigationResult backNow() {
        if (!this.active) {
            return NavigationResult.SESSION_ENDED;
        }
        AbstractWindow<?> source = this.sourceWindow();
        return source == null ? NavigationResult.AT_ROOT : this.openNow(source);
    }

    /**
     * 把 Window 记为新的链顶, 不在链上时压入, 已在链上时把它上方的层级截断.
     *
     * @param window 已经打开的 Window
     */
    void advanceTo(@NotNull Window window) {
        int index = this.chain.indexOf(window);
        if (index < 0) {
            this.chain.add(window);
        } else if (index < this.chain.size() - 1) {
            this.chain.subList(index + 1, this.chain.size()).clear();
        }
        this.chainSnapshot = List.copyOf(this.chain);
    }

    /**
     * 返回当前链顶 Window. 只在玩家实体线程调用.
     *
     * @return 链顶 Window, 链为空时为 null
     */
    @Nullable
    AbstractWindow<?> currentWindow() {
        return this.chain.isEmpty() ? null : (AbstractWindow<?>) this.chain.get(this.chain.size() - 1);
    }

    /**
     * 返回链顶的来源 Window, 即 {@link #back()} 要回到的那一层. 只在玩家实体线程调用.
     *
     * @return 来源 Window, 链顶已是链底时为 null
     */
    @Nullable
    AbstractWindow<?> sourceWindow() {
        return this.chain.size() < 2 ? null : (AbstractWindow<?>) this.chain.get(this.chain.size() - 2);
    }

    /**
     * 链顶关闭之后决定去向: 玩家主动关闭且该窗口要求返回时回到来源, 其余情况会话以该原因结束.
     * <p>返回不成立时按结束处理, 会话不会停在一个已经关闭的链顶上.
     *
     * @param window 刚刚关闭的链顶
     * @param reason 关闭原因
     */
    void onChainTopClosed(@NotNull AbstractWindow<?> window, @NotNull InventoryCloseEvent.Reason reason) {
        if (reason == InventoryCloseEvent.Reason.PLAYER && window.backOnPlayerClose()) {
            AbstractWindow<?> source = this.sourceWindow();
            if (source != null && this.openNow(source) == NavigationResult.OPENED) {
                return;
            }
        }
        this.endNow(reason, false);
    }

    @NotNull
    @Override
    public CompletableFuture<EndResult> end() {
        boolean wasActive = this.active;
        return this.manager.submit(
                this.viewer,
                () -> this.endNow(InventoryCloseEvent.Reason.PLUGIN, true),
                () -> wasActive ? EndResult.ENDED : EndResult.ALREADY_ENDED
        );
    }

    /**
     * 在玩家实体线程结束会话.
     * 先迁移状态再关闭链顶, 因此链顶关闭不会重新进入本会话的决策; 结束处理器最后触发.
     *
     * @param reason 结束原因
     * @param closeCurrent 是否需要由本次结束关闭链顶
     * @return 结束结果
     */
    EndResult endNow(@NotNull InventoryCloseEvent.Reason reason, boolean closeCurrent) {
        if (!this.deactivate()) {
            return EndResult.ALREADY_ENDED;
        }
        this.manager.unpublishSession(this);

        AbstractWindow<?> current = this.currentWindow();
        this.clearChain();
        if (closeCurrent && current != null) {
            try {
                this.manager.closeNow(current, reason);
            } catch (RuntimeException | Error throwable) {
                this.manager.report("Failed to close Window session current Window", throwable);
            }
        }
        this.fireEndHandlers(reason);
        return EndResult.ENDED;
    }

    /**
     * 调度器意外退役时回收会话本地状态, 不触发结束处理器.
     * <p>与 {@link AbstractWindow#retireSession()} 同一处境: 已经没有可用的玩家实体线程来运行用户代码.
     */
    void retire() {
        if (this.deactivate()) {
            this.clearChain();
        }
    }

    // 把会话迁移到已结束状态, 之后所有导航都不再接管; 本次调用完成迁移时返回 true.
    private boolean deactivate() {
        if (!this.active) {
            return false;
        }
        this.active = false;
        return true;
    }

    // 清空会话链, 只在玩家实体线程调用.
    private void clearChain() {
        this.chain.clear();
        this.chainSnapshot = List.of();
    }

    // 运行结束处理器.
    private void fireEndHandlers(@NotNull InventoryCloseEvent.Reason reason) {
        this.endHandlers.forEachIsolated(
                handler -> handler.accept(reason),
                "Failed to handle Window session end",
                this.manager::report
        );
    }

    @NotNull
    @Override
    public Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super WindowSession> callback) {
        return this.signalBindings.add(signal.onDirty(() -> callback.accept(this)));
    }

    @Override
    public void setEndHandlers(@NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> endHandlers) {
        this.endHandlers.set(HandlerList.copyConsumers(endHandlers));
    }

    @NotNull
    @Override
    @Unmodifiable
    public List<Consumer<InventoryCloseEvent.Reason>> getEndHandlers() {
        return this.endHandlers.snapshot();
    }

    @Override
    public void addEndHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> endHandler) {
        this.endHandlers.append(HandlerList.narrowConsumer(endHandler));
    }

    @Override
    public void removeEndHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> endHandler) {
        this.endHandlers.remove(HandlerList.narrowConsumer(endHandler));
    }

    @NotNull
    @Override
    public Player viewer() {
        return this.viewer;
    }

    @Nullable
    @Override
    public Window current() {
        List<Window> snapshot = this.chainSnapshot;
        return snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1);
    }

    @NotNull
    @Override
    @Unmodifiable
    public List<Window> chain() {
        return this.chainSnapshot;
    }

    @Override
    public boolean active() {
        return this.active;
    }

}
