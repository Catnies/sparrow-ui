package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.HandlerList;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 会话的共享骨架: 生命周期, 关闭去向决策与查询快照.
 * <p>成员结构(栈或树)与步入, 回退, 离场处置由 {@link WindowSession.Kind} 对应的子类给出;
 * 实际打开与关闭 Window 仍由 {@link WindowManager} 完成.
 * <p>成员结构只在玩家实体线程修改, 查询走 volatile 快照.
 */
abstract class AbstractWindowSession implements WindowSession {
    final WindowManager manager;
    private final Player viewer;
    private final HandlerList<Consumer<InventoryCloseEvent.Reason>> endHandlers;
    private final Bindings bindings = new Bindings(); // 本会话持有的 Signal 绑定

    private volatile List<Window> chainSnapshot = List.of(); // 最近一次已应用的当前路径快照
    private final AtomicBoolean active = new AtomicBoolean(true); // 会话是否尚未结束

    AbstractWindowSession(@NotNull WindowManager manager, @NotNull Player viewer, @NotNull List<Consumer<InventoryCloseEvent.Reason>> endHandlers) {
        this.manager = manager;
        this.viewer = viewer;
        this.endHandlers = new HandlerList<>(endHandlers);
    }

    /**
     * 按根窗的声明创建会话并把根收为第一个成员. 只在玩家实体线程调用.
     *
     * @param manager Window 管理器
     * @param root 已经打开的根窗
     * @return 新会话, 类型与结束处理器取自根窗的 Builder 声明
     */
    @NotNull
    static AbstractWindowSession create(@NotNull WindowManager manager, @NotNull AbstractWindow<?> root) {
        AbstractWindowSession session = switch (root.rootSessionKind()) {
            case STACK -> new WindowSessionStack(manager, root.viewer(), root.rootSessionEndHandlers());
            case RETAINED_STACK -> new WindowSessionRetainedStack(manager, root.viewer(), root.rootSessionEndHandlers());
            case TREE -> new WindowSessionTree(manager, root.viewer(), root.rootSessionEndHandlers());
        };
        session.commitOpen(root, false);
        return session;
    }

    /**
     * 把打开成功的 Window 落地为当前位置.
     * <p>由 {@link WindowManager} 在目标窗的 open handler 之前调用, handler 因此总能读到指向本窗的会话.
     *
     * @param opened 已经打开的目标 Window
     * @param back 本次打开是回到上一扇
     */
    void commitOpen(@NotNull AbstractWindow<?> opened, boolean back) {
        if (back) {
            this.stepBack();
        } else {
            this.stepInto(opened);
            opened.session(this);
        }
        this.publishSnapshot();
    }

    /**
     * 在玩家实体线程打开下一扇 Window 并推进当前位置.
     *
     * @param next 要打开的 Window
     * @return 打开并推进完成时返回 true
     */
    final boolean navigateNow(@NotNull AbstractWindow<?> next) {
        if (!this.active.get()) return false;
        // 推进当前位置由 openNow 在 open handler 之前完成, 打开失败时当前位置原样不动
        return this.manager.openInSession(next, this, false);
    }

    /**
     * 在玩家实体线程回到上一扇, 上一扇以原实例重新打开.
     *
     * @return 发生了返回时返回 true
     */
    final boolean backNow() {
        if (!this.active.get()) return false;
        AbstractWindow<?> source = this.previousWindow();
        return source != null && this.manager.openInSession(source, this, true);
    }

    @NotNull
    @Override
    public CompletableFuture<EndResult> end() {
        boolean wasActive = this.active.get();
        return this.manager.submit(
                this.viewer,
                () -> this.endNow(InventoryCloseEvent.Reason.PLUGIN, true),
                () -> wasActive ? EndResult.ENDED : EndResult.ALREADY_ENDED
        );
    }

    /**
     * 在玩家实体线程结束会话.
     * 先迁移状态再关闭当前窗, 因此当前窗关闭不会重新进入本会话的决策; 结束处理器最后触发.
     *
     * @param reason 结束原因
     * @param closeCurrent 是否需要由本次结束关闭当前窗
     * @return 结束结果
     */
    final EndResult endNow(@NotNull InventoryCloseEvent.Reason reason, boolean closeCurrent) {
        if (!this.deactivate()) {
            return EndResult.ALREADY_ENDED;
        }

        AbstractWindow<?> current = this.currentWindow();
        this.releaseMembers();
        this.chainSnapshot = List.of();
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
     * 当前窗关闭之后决定去向: 玩家主动关闭且该窗口要求返回时回到上一扇, 其余情况会话以该原因结束.
     * <p>返回不成立时按结束处理, 会话不会停在一个已经关闭的当前窗上.
     *
     * @param window 刚刚关闭的当前窗
     * @param reason 关闭原因
     */
    void onChainTopClosed(@NotNull AbstractWindow<?> window, @NotNull InventoryCloseEvent.Reason reason) {
        if (reason == InventoryCloseEvent.Reason.PLAYER && window.backOnPlayerClose() && this.backNow()) {
            return;
        }
        this.endNow(reason, false);
    }

    /**
     * 调度器意外退役时回收会话本地状态, 不触发结束处理器.
     * <p>与 {@link AbstractWindow#retireSession()} 同一处境: 已经没有可用的玩家实体线程来运行用户代码.
     */
    void retire() {
        if (this.deactivate()) {
            this.releaseMembers();
            this.chainSnapshot = List.of();
        }
    }

    // 把会话迁移到已结束状态, 之后所有导航都不再接管; 本次调用完成迁移时返回 true.
    // 结束可能同时来自会话自身, 关闭去向决策与 shutdown, 迁移必须原子, 释放成员与结束处理器才恰好跑一次.
    private boolean deactivate() {
        return this.active.compareAndSet(true, false);
    }

    // 运行结束处理器.
    private void fireEndHandlers(@NotNull InventoryCloseEvent.Reason reason) {
        this.endHandlers.forEachIsolated(
                handler -> handler.accept(reason),
                "Failed to handle Window session end",
                this.manager::report
        );
    }

    /**
     * 把刚打开的 Window 推进为当前位置.
     *
     * @param next 已经打开的 Window
     */
    abstract void stepInto(@NotNull AbstractWindow<?> next);

    /**
     * 从当前位置返回上一扇.
     */
    abstract void stepBack();

    /**
     * 当前位置的 Window.
     *
     * @return 当前位置的 Window, 没有成员时为 null
     */
    @Nullable
    abstract AbstractWindow<?> currentWindow();

    /**
     * 当前位置的上一层, 即 {@link #backNow()} 要回到的那一层.
     *
     * @return 上一扇 Window, 当前位置已是根窗时为 null.
     */
    @Nullable
    abstract AbstractWindow<?> previousWindow();

    /**
     * 返回根窗到当前位置的路径.
     *
     * @return 当前路径, 根窗在前
     */
    @NotNull
    abstract List<Window> currentPath();

    /**
     * 释放全部成员, 逐个解除 Window 的会话归属并清空成员结构.
     */
    abstract void releaseMembers();

    // 重新发布当前路径快照.
    final void publishSnapshot() {
        this.chainSnapshot = List.copyOf(this.currentPath());
    }

    @NotNull
    @Override
    public Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super WindowSession> callback) {
        return this.bindings.bind(() -> signal.onDirty(() -> callback.accept(this)));
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
    public boolean hasBack() {
        return this.chainSnapshot.size() > 1;
    }

    @Override
    public boolean active() {
        return this.active.get();
    }
}
