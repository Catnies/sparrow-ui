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

import java.util.List;
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

/**
 * 保存每名玩家当前活动的 Window, 并串行执行该玩家的生命周期命令.
 */
public final class WindowManager implements Listener {
    private final MenuFactory menuFactory;
    private final WindowScheduler scheduler;
    private final BukkitInventoryBridge bukkitBridge;
    private final BiConsumer<? super String, ? super Throwable> exceptionHandler;
    private final Map<UUID, AbstractWindow<?>> active = new ConcurrentHashMap<>();
    private final Map<UUID, WindowSessionImpl> sessions = new ConcurrentHashMap<>(); // 链顶正是玩家活动 Window 的会话
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
                () -> this.openNow(window, null),
                () -> Window.OpenResult.VIEWER_UNAVAILABLE
        );
    }

    /**
     * 在玩家实体线程完成打开流程.
     * 先完成新窗口初始化再发布 active 映射, 随后才关闭被替换的旧窗口.
     *
     * @param window 要打开的 Window
     * @param navigating 发起本次打开的会话, 链外打开为 null
     */
    private Window.OpenResult openNow(AbstractWindow<?> window, @Nullable WindowSessionImpl navigating) {
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

        // 顶替的是别的链顶就是链外打开, 本次导航自己的链顶属链内交接, 会话不结束
        WindowSessionImpl displaced = this.sessions.get(viewer.getUniqueId());
        if (displaced == navigating) {
            displaced = null;
        }
        AbstractWindow<?> previous = this.active.get(viewer.getUniqueId());
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
        // 链顶已随本次打开一并关闭, 会话只做自身收尾
        if (displaced != null) {
            displaced.endNow(InventoryCloseEvent.Reason.OPEN_NEW, false);
        }
        window.fireOpenHandlers();
        return Window.OpenResult.OPENED;
    }

    /**
     * 会话打开 Window 时使用的原语, 由会话在自己的导航命令内调用.
     *
     * @param window 要打开的 Window
     * @param session 发起本次打开的会话
     * @return 打开结果
     */
    Window.OpenResult openInSession(AbstractWindow<?> window, WindowSessionImpl session) {
        return this.openNow(window, session);
    }

    /**
     * 公布会话为该玩家当前占用活动 Window 的那一个.
     *
     * @param session 已经打开了链顶的会话
     */
    void publishSession(WindowSessionImpl session) {
        this.sessions.put(session.viewer().getUniqueId(), session);
    }

    /**
     * 撤下会话, 之后它不再参与链外打开与关闭去向的判定.
     *
     * @param session 已经结束的会话
     */
    void unpublishSession(WindowSessionImpl session) {
        this.sessions.remove(session.viewer().getUniqueId(), session);
    }

    /**
     * 请求从一个已经打开的 Window 出发打开下一扇 Window.
     * source 在会话里就加入那条链, 不在就与它组成一条新链, 因此新窗口总是可以返回 source.
     *
     * @param source 来源 Window
     * @param next 要打开的下一扇 Window
     * @return 打开后的 Window, 打不开时为 null
     */
    @NotNull
    CompletableFuture<Window> openNext(AbstractWindow<?> source, AbstractWindow<?> next) {
        return this.submit(
                next.viewer(),
                () -> this.openNextNow(source, next) == WindowSession.NavigationResult.OPENED ? next : null,
                () -> null
        );
    }

    // 在玩家实体线程解析来源所属的会话, 必要时新起一条链.
    private WindowSession.NavigationResult openNextNow(AbstractWindow<?> source, AbstractWindow<?> next) {
        WindowSessionImpl session = this.sessions.get(source.viewer().getUniqueId());
        if (session != null && session.currentWindow() == source) {
            return session.openNow(next);
        }

        // source 不在任何链上就让它当链底, 新会话在首次打开时才公布, 旧会话因此照常按链外打开结束
        WindowSessionImpl started = new WindowSessionImpl(this, source.viewer(), List.of());
        started.advanceTo(source);
        return started.openNow(next);
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
        this.afterChainTopClosed(window, reason);
        return closed ? Window.CloseResult.CLOSED : Window.CloseResult.ALREADY_CLOSED;
    }

    /**
     * 链顶关闭之后由会话决定去向, 玩家主动关闭且该窗口要求返回时回到来源, 其余情况会话以该原因结束.
     * <p>只有正占用玩家活动窗口的会话参与决策, 因此链下窗口的关闭与会话自身发起的结束都不会进入这里.
     * 返回与结束都发生在关闭流程内, 与关闭本身同处一条命令.
     *
     * @param window 刚刚关闭的 Window
     * @param reason 关闭原因
     */
    private void afterChainTopClosed(AbstractWindow<?> window, InventoryCloseEvent.Reason reason) {
        WindowSessionImpl session = this.sessions.get(window.viewer().getUniqueId());
        if (session == null || session.currentWindow() != window) {
            return;
        }
        session.onChainTopClosed(window, reason);
    }

    /**
     * 请求回到会话的上一层.
     *
     * @param window 要离开的 Window
     * @param closeAtRoot 没有上一层可回时是否改为关闭该 Window
     * @return 返回后的新链顶, 没有发生返回时为 null
     */
    @NotNull
    CompletableFuture<Window> back(AbstractWindow<?> window, boolean closeAtRoot) {
        return this.submit(
                window.viewer(),
                () -> this.backNow(window, closeAtRoot),
                () -> null
        );
    }

    // 会话链顶有来源时返回上一层; 其余情况(链底, 不在会话里)按 closeAtRoot 决定关闭还是不做任何事.
    @Nullable
    private Window backNow(AbstractWindow<?> window, boolean closeAtRoot) {
        WindowSessionImpl session = this.sessions.get(window.viewer().getUniqueId());
        if (session != null && session.currentWindow() == window) {
            AbstractWindow<?> source = session.sourceWindow();
            if (source != null && session.backNow() == WindowSession.NavigationResult.OPENED) {
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
                    this.afterChainTopClosed(window, InventoryCloseEvent.Reason.DISCONNECT);
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
            this.afterChainTopClosed(window, InventoryCloseEvent.Reason.DISCONNECT);
        }

        PlayerCommandLane lane = this.lanes.get(playerId);
        if (lane != null && lane.belongsTo(player)) {
            lane.retire();
        }
    }

    // 正常断线应已由 InventoryCloseEvent 清理 Window, 若此处仍有打开 Window, 只本地注销并警告 handler 未执行.
    private void retire(UUID playerId, Player player, PlayerCommandLane lane) {
        this.lanes.remove(playerId, lane);
        WindowSessionImpl session = this.sessions.get(playerId);
        if (session != null && session.viewer() == player && this.sessions.remove(playerId, session)) {
            // 已经没有可用的实体线程, 只回收本地状态, 不触发结束处理器
            session.retire();
        }
        AbstractWindow<?> window = this.active.get(playerId);
        if (window == null
                || window.viewer() != player
                || !this.active.remove(playerId, window)) {
            return;
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
        // 先结束会话再逐个关闭 Window, 关闭流程因此不会再进入会话决策
        for (WindowSessionImpl session : Set.copyOf(this.sessions.values())) {
            try {
                session.endNow(InventoryCloseEvent.Reason.PLUGIN, false);
            } catch (RuntimeException | Error throwable) {
                this.report("Failed to end Window session during shutdown", throwable);
            }
        }
        this.sessions.clear();
        for (AbstractWindow<?> window : Set.copyOf(this.active.values())) {
            try {
                this.closeNow(window, InventoryCloseEvent.Reason.PLUGIN);
            } catch (RuntimeException | Error throwable) {
                this.report("Failed to close Window during shutdown", throwable);
            }
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
