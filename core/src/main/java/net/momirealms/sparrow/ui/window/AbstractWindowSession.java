package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.HandlerList;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

abstract class AbstractWindowSession implements WindowSession {
    final WindowManager manager;
    private final Player viewer;
    private final HandlerList<Consumer<WindowCloseReason>> sessionEndHandlers;
    private final Bindings bindings = new Bindings(); // 这个会话持有的 Signal 绑定, 结束时统一摘

    private volatile List<Window> chainSnapshot = List.of(); // 最近一次发布出去的路径快照, chain() 和 current() 都读它
    private final AtomicBoolean active = new AtomicBoolean(true); // 会话还没结束; 只能从 true 翻到 false

    AbstractWindowSession(@NotNull WindowManager manager, @NotNull Player viewer, @NotNull List<Consumer<WindowCloseReason>> sessionEndHandlers) {
        this.manager = manager;
        this.viewer = viewer;
        this.sessionEndHandlers = new HandlerList<>(sessionEndHandlers);
    }

    // 按根窗声明的类型建会话, 顺手把根窗收成第一个成员; 只在玩家实体线程调用.
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

    // 在派发 open handler 之前先落位, 回调里读到的 current 于是已经是新的那一扇.
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
     * 在玩家实体线程打开下一扇, 并把当前位置推进过去.
     *
     * @param next 要打开的 Window
     * @return 打开且推进完成时为 true
     */
    final boolean navigateNow(@NotNull AbstractWindow<?> next) {
        if (!this.active.get()) return false;
        // 位置的推进由 openInSession 在派发 open handler 之前做掉; 打开失败的话位置一步都不动
        return this.manager.openInSession(next, this, false);
    }

    /**
     * 在玩家实体线程退回上一扇, 上一扇是拿原实例重新打开的.
     *
     * @return 真的发生返回时为 true
     */
    final boolean backNow() {
        if (!this.active.get()) return false;
        AbstractWindow<?> source = this.previousWindow();
        return source != null && this.manager.openInSession(source, this, true);
    }

    @NotNull
    @Override
    public CompletableFuture<EndResult> end() {
        // 结束动作提交到玩家实体线程去执行, 结果按提交那一刻的状态回报
        boolean wasActive = this.active.get();
        return this.manager.submit(
                this.viewer,
                () -> this.endNow(WindowCloseReason.PLUGIN, true),
                () -> wasActive ? EndResult.ENDED : EndResult.ALREADY_ENDED
        );
    }

    /**
     * 在玩家实体线程结束会话.
     * <p>先把状态迁移掉再关当前窗, 所以关窗不会再绕回本会话的决策里; 结束处理器放在最后跑.
     *
     * @param reason 结束原因
     * @param closeCurrent 是否需要由本次结束关闭当前窗
     * @return 结束结果
     */
    final EndResult endNow(@NotNull WindowCloseReason reason, boolean closeCurrent) {
        if (!this.deactivate()) {
            return EndResult.ALREADY_ENDED;
        }

        AbstractWindow<?> current = this.currentWindow();
        this.releaseMembers();
        this.chainSnapshot = List.of();
        this.detachBindings();
        if (closeCurrent && current != null) {
            try {
                this.manager.closeNow(current, reason);
            } catch (RuntimeException | Error throwable) {
                SparrowUI.getInstance().handleException("Failed to close Window session current Window", throwable);
            }
        }
        this.fireSessionEndHandlers(reason);
        return EndResult.ENDED;
    }

    // 玩家主动关的, 而且这条路径允许返回, 就退回上一扇; 别的情况一律结束整段会话.
    void onChainTopClosed(@NotNull AbstractWindow<?> window, @NotNull WindowCloseReason reason) {
        if (reason == WindowCloseReason.PLAYER && window.backOnPlayerClose() && this.backNow()) {
            return;
        }
        this.endNow(reason, false);
    }

    // 调度器退役之后没有可用的玩家实体线程了, 只回收状态, 用户结束处理器不跑.
    void retire() {
        if (this.deactivate()) {
            this.releaseMembers();
            this.chainSnapshot = List.of();
            this.detachBindings();
        }
    }

    // 会话已经不会再激活, 绑定当场摘掉, 不等它被回收.
    private void detachBindings() {
        try {
            this.bindings.suspendAll();
        } catch (RuntimeException | Error throwable) {
            SparrowUI.getInstance().handleException("Failed to detach Window session bindings", throwable);
        }
    }

    // 把会话推进到已结束状态, 之后所有导航都不再接管; 这次真的完成迁移时返回 true.
    // 结束可能同时来自会话自己, 关窗去向决策和 shutdown, 迁移必须原子, 释放成员和结束处理器才只跑一次.
    private boolean deactivate() {
        return this.active.compareAndSet(true, false);
    }

    // 把结束处理器挨个跑一遍, 一个抛了不影响后面的
    private void fireSessionEndHandlers(@NotNull WindowCloseReason reason) {
        this.sessionEndHandlers.forEachIsolated(
                handler -> handler.accept(reason),
                "Failed to handle Window session end",
                SparrowUI.getInstance()::handleException
        );
    }

    /**
     * 把刚打开的 Window 摆成当前位置.
     *
     * @param next 已经打开的 Window
     */
    abstract void stepInto(@NotNull AbstractWindow<?> next);

    /**
     * 从当前位置退回上一扇.
     */
    abstract void stepBack();

    @Nullable
    abstract AbstractWindow<?> currentWindow();

    @Nullable
    abstract AbstractWindow<?> previousWindow();

    @NotNull
    abstract List<Window> currentPath();

    /**
     * 放掉全部成员: 挨个解除 Window 的会话归属, 再清空成员结构.
     */
    abstract void releaseMembers();

    // 重新发布路径快照, 读的人下一眼看到的就是新路径
    final void publishSnapshot() {
        this.chainSnapshot = List.copyOf(this.currentPath());
    }

    @NotNull
    @Override
    public Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super WindowSession> callback) {
        // 绑定挂在会话上, 会话结束就一起摘掉
        return this.bindings.bind(() -> signal.onDirty(() -> callback.accept(this)));
    }

    @Override
    public void setSessionEndHandlers(@NotNull List<? extends Consumer<? super WindowCloseReason>> sessionEndHandlers) {
        this.sessionEndHandlers.set(HandlerList.copyConsumers(sessionEndHandlers));
    }

    @NotNull
    @Override
    @Unmodifiable
    public List<Consumer<WindowCloseReason>> getSessionEndHandlers() {
        return this.sessionEndHandlers.snapshot();
    }

    @Override
    public void addSessionEndHandler(@NotNull Consumer<? super WindowCloseReason> sessionEndHandler) {
        this.sessionEndHandlers.append(HandlerList.narrowConsumer(sessionEndHandler));
    }

    @Override
    public void removeSessionEndHandler(@NotNull Consumer<? super WindowCloseReason> sessionEndHandler) {
        this.sessionEndHandlers.remove(HandlerList.narrowConsumer(sessionEndHandler));
    }

    @NotNull
    @Override
    public Player viewer() {
        return this.viewer;
    }

    @Nullable
    @Override
    public Window current() {
        // 快照的最后一格就是当前窗
        List<Window> snapshot = this.chainSnapshot;
        return snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1);
    }

    @NotNull
    @Override
    @Unmodifiable
    public List<Window> chain() {
        // 快照本身不可变, 直接给出去
        return this.chainSnapshot;
    }

    @Override
    public boolean hasBack() {
        // 路径里不止一扇才有上一扇
        return this.chainSnapshot.size() > 1;
    }

    @Override
    public boolean active() {
        return this.active.get();
    }
}
