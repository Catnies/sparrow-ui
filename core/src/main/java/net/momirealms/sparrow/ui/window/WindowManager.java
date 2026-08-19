package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.exception.ViewerUnavailableException;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuFactoryImpl;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public final class WindowManager implements Listener {
    private final MenuFactory menuFactory;
    private final WindowScheduler scheduler;
    private final BukkitInventoryBridge bukkitBridge;
    private final BiConsumer<? super String, ? super Throwable> exceptionHandler;
    private final Map<UUID, AbstractWindow<?>> active = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerCommandLane> lanes = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final AtomicBoolean shutdown = new AtomicBoolean();

    WindowManager(Plugin plugin) {
        this(plugin, new MenuFactoryImpl(plugin));
    }

    WindowManager(Plugin plugin, MenuFactory menuFactory) {
        this.menuFactory = menuFactory;
        this.scheduler = new WindowScheduler(plugin);
        this.exceptionHandler = SparrowUI.getInstance()::handleException;
        this.bukkitBridge = new BukkitInventoryBridge(this.exceptionHandler);
    }

    @NotNull
    public static WindowManager getInstance() {
        return SparrowUI.getInstance().windowManager();
    }

    /**
     * 创建并注册 WindowManager.
     *
     * @return 已注册的 WindowManager
     */
    @NotNull
    public static WindowManager create() {
        Plugin plugin = SparrowUI.getInstance().getPlugin();
        WindowManager manager = new WindowManager(plugin);
        Bukkit.getPluginManager().registerEvents(manager, plugin);
        return manager;
    }

    /**
     * 请求开启 Window, 打开命令会进入该玩家的命令通道并在实体线程执行.
     *
     * @param window 要打开的 Window
     * @return 打开结果阶段
     */
    @NotNull
    CompletableFuture<Window.OpenResult> open(AbstractWindow<?> window) {
        return this.submit(
                window,
                () -> this.openNow(window, null, false),
                () -> Window.OpenResult.VIEWER_UNAVAILABLE
        );
    }

    /**
     * 在玩家实体线程完成打开流程.
     * 先完成新窗口初始化再发布 active 映射, 随后才关闭被替换的旧窗口.
     *
     * @param window 要打开的 Window
     * @param transitionSession 发起本次打开的会话, 会话外打开为 null
     * @param back 会话内打开时, 本次打开是否为回到上一扇
     */
    private Window.OpenResult openNow(AbstractWindow<?> window, @Nullable AbstractWindowSession transitionSession, boolean back) {
        if (this.shutdown.get()) {
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }
        if (window.isOpen()) {
            return Window.OpenResult.ALREADY_OPEN;
        }
        Player viewer = window.viewer();
        if (!viewer.isValid() || !viewer.isConnected() || viewer.isSleeping()) {
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }

        // 顶替的是别的当前窗就是会话外打开, 本次导航自己的当前窗属会话内交接, 会话不结束
        AbstractWindow<?> previous = this.active.get(viewer.getUniqueId());
        AbstractWindowSession displaced = previous == null ? null : previous.sessionImpl();
        if (displaced == transitionSession) {
            displaced = null;
        }
        boolean replaceWindow = previous != null && previous != window;
        try {
            window.openOnViewerEntity(this.generations.getAndIncrement(), replaceWindow);
        } catch (ViewerUnavailableException ignored) {
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }

        this.active.put(viewer.getUniqueId(), window);
        if (replaceWindow) {
            try {
                previous.closeOnViewerEntity(InventoryCloseEvent.Reason.OPEN_NEW);
            } catch (RuntimeException | Error throwable) {
                this.report("Failed to clean up replaced Window", throwable);
            }
        }
        if (this.shutdown.get()) {
            this.active.remove(viewer.getUniqueId(), window);
            window.closeOnViewerEntity(InventoryCloseEvent.Reason.PLUGIN);
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }
        // 被顶掉的当前窗已随本次打开一并关闭, 会话只做自身收尾
        if (displaced != null) {
            displaced.endNow(InventoryCloseEvent.Reason.OPEN_NEW, false);
        }
        // 会话状态在 open handler 之前落地, 会话外打开的窗成为新根窗, 会话在此刻诞生, 会话内打开则推进当前位置
        if (transitionSession == null) {
            window.session(AbstractWindowSession.create(this, window));
        } else {
            transitionSession.commitOpen(window, back);
        }
        window.fireOpenHandlers();
        return Window.OpenResult.OPENED;
    }

    /**
     * 会话打开 Window 时使用的原语, 由会话在自己的导航命令内调用.
     *
     * @param window 要打开的 Window
     * @param session 发起本次打开的会话
     * @param back 本次打开是回到上一扇, 而不是步入下一扇
     * @return 打开流程执行成功时返回 true
     */
    boolean openInSession(AbstractWindow<?> window, AbstractWindowSession session, boolean back) {
        return this.openNow(window, session, back) == Window.OpenResult.OPENED;
    }

    /**
     * 请求从一个已经打开的 Window 出发打开下一扇 Window.
     * source 是当前窗就沿它的会话推进, 不是就先让 source 成为新根窗, 因此新窗口总是可以返回 source.
     *
     * @param source 上一扇 Window
     * @param next 要打开的下一扇 Window
     * @return 打开后的 Window, 打不开时为 null
     */
    @NotNull
    CompletableFuture<Window> navigate(AbstractWindow<?> source, AbstractWindow<?> next) {
        return this.submit(
                next.viewer(),
                () -> this.navigateNow(source, next) ? next : null,
                () -> null
        );
    }

    /**
     * 等待一扇还在构建中的 Window 完成, 再从出发窗打开它.
     * <p>发起时先记下出发窗当时的挂载. 构建结果到达时出发窗已经关闭, 被顶替, 或者不再是所在会话的当前窗,
     * 本次导航就此作罢: 不打开任何窗口, 原会话与玩家正在看的菜单都不改变.
     *
     * @param source 上一扇 Window
     * @param next 构建中的下一扇 Window
     * @return 打开后的 Window; 打不开或出发窗已经离开原位置时以 null 完成
     */
    @NotNull
    CompletableFuture<Window> navigateLater(AbstractWindow<?> source, CompletionStage<? extends Window> next) {
        AbstractWindowSession mount = source.sessionImpl();
        return next.<Window>thenCompose(window -> {
            AbstractWindow<?> target = source.requireSameViewer(window);
            return this.submit(
                    target.viewer(),
                    // 检查出发窗是否还停在发起导航时的位置, 会话归属没换过, 并且仍是那段会话的当前窗.
                    () -> (source.sessionImpl() == mount && (mount == null || mount.currentWindow() == source)) && this.navigateNow(source, target)
                            ? target
                            : null,
                    () -> null
            );
        }).toCompletableFuture();
    }

    // 在玩家实体线程解析出发窗所属的会话, 必要时新起一段会话.
    private boolean navigateNow(AbstractWindow<?> source, AbstractWindow<?> next) {
        AbstractWindowSession session = source.sessionImpl();
        // 仍是会话成员却已不在当前位置: 位置早就不在出发窗上, 不再新起一段会话去覆盖它的归属
        if (session != null) {
            return session.currentWindow() == source && session.navigateNow(next);
        }

        // source 不在任何会话中就让它当根窗; 旧会话在 openNow 里照常按会话外打开结束
        return AbstractWindowSession.create(this, source).navigateNow(next);
    }

    /**
     * 为 Window 启动玩家的实体线程上的周期 tick.
     * 实体退役时通过 lane 的退役回调回收整个会话.
     *
     * @param window 要 tick 的 Window
     * @return tick 任务, shutdown 后为 null
     */
    @Nullable
    ScheduledTask startTick(AbstractWindow<?> window) {
        if (this.shutdown.get()) {
            return null;
        }
        PlayerCommandLane lane = this.lane(window.viewer());
        ScheduledTask task;
        try {
            task = this.scheduler.entity().runAtFixedRate(window.viewer(), window::tick, lane::retire, 1, 1);
        } catch (RuntimeException | Error throwable) {
            lane.fail(throwable);
            throw throwable;
        }
        if (task == null) {
            lane.retire();
        }
        return task;
    }

    /**
     * 请求关闭 Window, 关闭命令会进入该玩家的命令通道并在实体线程执行.
     *
     * @param window 要关闭的 Window
     * @return 关闭结果阶段
     */
    @NotNull
    CompletableFuture<Window.CloseResult> close(AbstractWindow<?> window) {
        boolean wasOpen = window.isOpen();
        return this.submit(
                window,
                () -> this.closeNow(window, InventoryCloseEvent.Reason.PLUGIN),
                () -> wasOpen ? Window.CloseResult.CLOSED : Window.CloseResult.ALREADY_CLOSED
        );
    }

    // 在玩家实体线程关闭 Window 并移除 active 映射.
    Window.CloseResult closeNow(AbstractWindow<?> window, InventoryCloseEvent.Reason reason) {
        if (!window.isOpen()) return Window.CloseResult.ALREADY_CLOSED;

        this.active.remove(window.viewer().getUniqueId(), window);
        boolean closed = window.closeOnViewerEntity(reason);
        this.afterCurrentWindowClosed(window, reason);
        return closed ? Window.CloseResult.CLOSED : Window.CloseResult.ALREADY_CLOSED;
    }

    /**
     * 当前窗关闭之后由会话决定去向, 玩家主动关闭且该窗口要求返回时回到上一扇, 其余情况会话以该原因结束.
     * <p>只有正占用玩家活动窗口的会话参与决策, 因此当前窗之外成员的关闭与会话自身发起的结束都不会进入这里.
     * 返回与结束都发生在关闭流程内, 与关闭本身同处一条命令.
     *
     * @param window 刚刚关闭的 Window
     * @param reason 关闭原因
     */
    private void afterCurrentWindowClosed(AbstractWindow<?> window, InventoryCloseEvent.Reason reason) {
        AbstractWindowSession session = window.sessionImpl();
        if (session == null || session.currentWindow() != window) {
            return;
        }
        session.onChainTopClosed(window, reason);
    }

    /**
     * 请求回到上一扇.
     *
     * @param window 要离开的 Window
     * @param closeAtRoot 没有上一层可回时是否改为关闭该 Window
     * @return 返回后的新当前窗, 没有发生返回时为 null
     */
    @NotNull
    CompletableFuture<Window> back(AbstractWindow<?> window, boolean closeAtRoot) {
        return this.submit(
                window.viewer(),
                () -> this.backNow(window, closeAtRoot),
                () -> null
        );
    }

    // 会话当前窗有上一扇时返回; 其余情况(根窗, 不在会话里)按 closeAtRoot 决定关闭还是不做任何事.
    @Nullable
    private Window backNow(AbstractWindow<?> window, boolean closeAtRoot) {
        AbstractWindowSession session = window.sessionImpl();
        if (session != null && session.currentWindow() == window) {
            AbstractWindow<?> source = session.previousWindow();
            if (source != null && session.backNow()) {
                return source;
            }
        }
        if (closeAtRoot) {
            this.closeNow(window, InventoryCloseEvent.Reason.PLUGIN);
        }
        return null;
    }

    /**
     * 将普通 Window 命令串行化到玩家的实体线程.
     * Shutdown 后不再调度, 立即执行 retiredAction 并以其结果完成.
     *
     * @param window 目标 Window
     * @param action 正常调度时执行的命令
     * @param retiredAction 无法调度时执行的替代命令
     * @param <T> 命令结果类型
     * @return 命令完成阶段
     */
    @NotNull
    <T> CompletableFuture<T> submit(AbstractWindow<?> window, Callable<T> action, Callable<T> retiredAction) {
        return this.submit(window.viewer(), action, retiredAction);
    }

    /**
     * 将命令串行化到玩家的实体线程.
     * Shutdown 后不再调度, 立即执行 retiredAction 并以其结果完成.
     *
     * @param viewer 目标玩家
     * @param action 正常调度时执行的命令
     * @param retiredAction 无法调度时执行的替代命令
     * @param <T> 命令结果类型
     * @return 命令完成阶段
     */
    @NotNull
    <T> CompletableFuture<T> submit(Player viewer, Callable<T> action, Callable<T> retiredAction) {
        if (!this.shutdown.get()) {
            // 通道给的是只读阶段, toCompletableFuture 每次生成一个独立的 future:
            // 调用方取消自己拿到的这一个, 既动不了队列里的命令, 也不影响别人的观察.
            return this.lane(viewer).submit(action, retiredAction).toCompletableFuture();
        }
        try {
            return CompletableFuture.completedFuture(retiredAction.call());
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }

    /**
     * 返回玩家的命令通道, 不存在时创建并注册退役回调.
     * 同 UUID 的旧 Player 通道先被退役, 其迟到回调不能移除新通道.
     * Shutdown 与新通道创建竞争时, 立即退役刚创建的通道.
     *
     * @param player 玩家
     * @return 玩家的命令通道
     */
    private PlayerCommandLane lane(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerCommandLane lane;
        while (true) {
            PlayerCommandLane current = this.lanes.get(playerId);
            if (current != null) {
                if (current.belongsTo(player)) {
                    lane = current;
                    break;
                }
                if (this.lanes.remove(playerId, current)) {
                    current.retire();
                }
                continue;
            }

            PlayerCommandLane candidate = new PlayerCommandLane(
                    player,
                    this.scheduler,
                    retiredLane -> this.retire(playerId, player, retiredLane)
            );
            if (this.lanes.putIfAbsent(playerId, candidate) == null) {
                lane = candidate;
                break;
            }
        }
        if (this.shutdown.get() && this.lanes.remove(playerId, lane)) {
            lane.retire();
        }
        return lane;
    }

    /**
     * 统一上报异步命令的失败.
     *
     * @param stage 命令阶段
     * @param message 失败报告文本
     */
    private void reportFailure(CompletionStage<?> stage, String message) {
        stage.exceptionally(throwable -> {
            this.report(message, throwable);
            return null;
        });
    }

    /**
     * 将内部异常交给插件的统一异常处理器.
     *
     * @param message 错误说明
     * @param throwable 异常
     */
    void report(String message, Throwable throwable) {
        try {
            this.exceptionHandler.accept(message, throwable);
        } catch (Throwable reportingFailure) {
            if (reportingFailure != throwable) {
                ThrowableUtils.combine(throwable, reportingFailure);
            }
        }
    }

    /**
     * Bukkit 观测到容器关闭时, 若 InventoryView 属于某个活动 Window 则按外部关闭处理.
     * 断线关闭已经由服务器接管, 事件返回后会继续完成容器生命周期, 因此必须在事件内同步通知 handler;
     * 其他原因仍延后到下一实体 tick, 保留 close handler 打开新 Window 的既有能力.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void handleInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            AbstractWindow<?> window = this.active.get(player.getUniqueId());
            if (window == null || !window.ownsInventoryView(event.getView())) {
                return;
            }

            if (event.getReason() == InventoryCloseEvent.Reason.DISCONNECT) {
                if (this.active.remove(player.getUniqueId(), window)) {
                    try {
                        window.closeAfterInventoryEvent(InventoryCloseEvent.Reason.DISCONNECT);
                    } catch (RuntimeException | Error throwable) {
                        this.report("Failed to process disconnected Window close", throwable);
                    }
                    this.afterCurrentWindowClosed(window, InventoryCloseEvent.Reason.DISCONNECT);
                }
                return;
            }

            PlayerCommandLane lane = this.lane(window.viewer());
            this.reportFailure(
                    lane.submitDeferred(
                            () -> {
                                this.closeNow(window, event.getReason());
                                return null;
                            },
                            () -> null
                    ),
                    "Failed to process external Window close"
            );
        }
    }

    // 玩家退出事件是 DISCONNECT 关闭事件的同步兜底, 随后注销对应 Player 实例的 lane.
    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        AbstractWindow<?> window = this.active.get(playerId);
        if (window != null && window.viewer() == player && this.active.remove(playerId, window)) {
            try {
                window.closeAfterInventoryEvent(InventoryCloseEvent.Reason.DISCONNECT);
            } catch (RuntimeException | Error throwable) {
                this.report("Failed to close Window after player quit", throwable);
            }
            this.afterCurrentWindowClosed(window, InventoryCloseEvent.Reason.DISCONNECT);
        }

        PlayerCommandLane lane = this.lanes.get(playerId);
        if (lane != null && lane.belongsTo(player)) {
            lane.retire();
        }
    }

    // 正常断线应已由 InventoryCloseEvent 清理 Window, 若此处仍有打开 Window, 只本地注销并警告 handler 未执行.
    private void retire(UUID playerId, Player player, PlayerCommandLane lane) {
        this.lanes.remove(playerId, lane);
        AbstractWindow<?> window = this.active.get(playerId);
        if (window == null
                || window.viewer() != player
                || !this.active.remove(playerId, window)) {
            return;
        }

        AbstractWindowSession session = window.sessionImpl();
        if (session != null) {
            // 已经没有可用的实体线程, 只回收本地状态, 不触发结束处理器
            session.retire();
        }
        boolean wasOpen = window.retireSession();
        if (!wasOpen) {
            return;
        }
        this.report(
                "Window entity scheduler retired before close handlers ran"
                        + " [player=" + playerId
                        + ", window=" + window.getClass().getName() + "]",
                new IllegalStateException("viewer entity scheduler retired before InventoryCloseEvent")
        );
    }

    // 在插件禁用时直接按 PLUGIN 原因关闭所有活动 Window.
    public void shutdown() {
        if (!this.shutdown.compareAndSet(false, true)) {
            return;
        }
        for (AbstractWindow<?> window : Set.copyOf(this.active.values())) {
            PlayerCommandLane lane = this.lanes.get(window.viewer().getUniqueId());
            // 通道已经注销或已经属于重连后的新 Player, 这扇窗只能由本次 shutdown 直接收尾
            if (lane == null || !lane.belongsTo(window.viewer())) {
                this.shutdownNow(window);
                continue;
            }
            lane.terminate(() -> this.shutdownNow(window));
        }
        this.active.clear();
        for (PlayerCommandLane lane : Set.copyOf(this.lanes.values())) {
            lane.retire();
        }
        this.lanes.clear();
        if (this.menuFactory instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                this.report("Failed to close Window menu backend", exception);
            }
        }
    }

    // 在玩家的命令通道内收尾一扇活动窗: 先结束会话再关闭 Window, 关闭流程因此不会再进入会话决策.
    private void shutdownNow(AbstractWindow<?> window) {
        AbstractWindowSession session = window.sessionImpl();
        if (session != null) {
            try {
                session.endNow(InventoryCloseEvent.Reason.PLUGIN, false);
            } catch (RuntimeException | Error throwable) {
                this.report("Failed to end Window session during shutdown", throwable);
            }
        }
        try {
            this.closeNow(window, InventoryCloseEvent.Reason.PLUGIN);
        } catch (RuntimeException | Error throwable) {
            this.report("Failed to close Window during shutdown", throwable);
        }
    }

    /**
     * 返回该玩家当前观察的 Window.
     *
     * @param player 要查询的玩家
     * @return 当前 Window, 没有时为 null
     */
    @Nullable
    public Window current(@NotNull Player player) {
        return this.active.get(player.getUniqueId());
    }

    /**
     * 返回当前活动 Window 的快照.
     *
     * @return 所有活动 Window
     */
    @NotNull
    @Unmodifiable
    public Set<Window> windows() {
        return Set.copyOf(this.active.values());
    }

    /**
     * 返回 Bukkit Inventory 事件桥接器.
     *
     * @return Bukkit 事件桥接器
     */
    BukkitInventoryBridge bukkitBridge() {
        return this.bukkitBridge;
    }

    /**
     * 返回创建菜单处理器的工厂.
     *
     * @return 菜单工厂
     */
    MenuFactory menuFactory() {
        return this.menuFactory;
    }
}
