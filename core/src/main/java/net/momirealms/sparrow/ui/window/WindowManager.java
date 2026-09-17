package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.exception.ViewerUnavailableException;
import net.momirealms.sparrow.ui.scheduler.executor.EntityExecutor;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.window.handle.MenuFactory;
import net.momirealms.sparrow.ui.window.handle.MenuFactoryImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
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

/**
 * 把 Window 命令按玩家串行送进实体线程, 同时维护每个玩家的活动窗口和会话.
 */
public final class WindowManager implements Listener {
    private final MenuFactory menuFactory;      // 建菜单用的工厂
    private final EntityExecutor entityScheduler; // 玩家实体线程的调度入口
    private final BukkitInventoryBridge bukkitBridge; // 把点击桥给 Bukkit 事件
    private final Map<UUID, AbstractWindow<?>> active = new ConcurrentHashMap<>(); // 玩家 -> 现在开着的那扇窗
    private final Map<UUID, PlayerCommandLane> lanes = new ConcurrentHashMap<>();  // 玩家 -> 命令通道, 命令靠它串行
    private final AtomicLong generations = new AtomicLong();                   // 打开的代数, 每开一次加一, 用来把迟到的输入挡掉
    private final AtomicBoolean shutdown = new AtomicBoolean();                // 是否已经在关服收尾

    WindowManager(Plugin plugin, EntityExecutor entityScheduler) {
        this(plugin, new MenuFactoryImpl(plugin), entityScheduler);
    }

    WindowManager(Plugin plugin, MenuFactory menuFactory, EntityExecutor entityScheduler) {
        this.menuFactory = menuFactory;
        this.entityScheduler = entityScheduler;
        this.bukkitBridge = new BukkitInventoryBridge();
    }

    @NotNull
    public static WindowManager getInstance() {
        return SparrowUI.getInstance().windowManager();
    }

    @NotNull
    @ApiStatus.Internal
    public static WindowManager create(@NotNull Plugin plugin, @NotNull EntityExecutor entityScheduler) {
        WindowManager manager = new WindowManager(plugin, entityScheduler);
        Bukkit.getPluginManager().registerEvents(manager, plugin);
        return manager;
    }

    // 打开命令走玩家通道, 串行送到实体线程
    @NotNull
    CompletableFuture<Window.OpenResult> open(AbstractWindow<?> window) {
        return this.submit(
                window,
                () -> this.openNow(window, null, false),
                () -> Window.OpenResult.VIEWER_UNAVAILABLE
        );
    }

    /**
     * 在玩家实体线程上把打开流程走完.
     * <p>先把新窗初始化好再发布 active 映射, 之后才去关被顶替的旧窗.
     *
     * @param window 要打开的 Window
     * @param transitionSession 发起本次打开的会话, 会话外打开为 null
     * @param back 会话内打开时, 本次打开是否为回到上一扇
     */
    private Window.OpenResult openNow(AbstractWindow<?> window, @Nullable AbstractWindowSession transitionSession, boolean back) {
        // 先看关没关服, 再看窗和玩家能不能用
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

        // 推一下会话归属: 同一段会话里换窗不算被顶替
        AbstractWindow<?> previous = this.active.get(viewer.getUniqueId());
        AbstractWindowSession displaced = previous == null ? null : previous.sessionImpl();
        if (displaced == transitionSession) {
            displaced = null;
        }
        boolean replaceWindow = previous != null && previous != window;
        // 在实体线程上把新窗初始化好
        try {
            window.openOnViewerEntity(this.generations.getAndIncrement(), replaceWindow);
        } catch (ViewerUnavailableException ignored) {
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }

        // 发布活动窗, 顺手收掉被顶替的那扇
        this.active.put(viewer.getUniqueId(), window);
        if (replaceWindow) {
            try {
                previous.closeOnViewerEntity(WindowCloseReason.OPEN_NEW);
            } catch (RuntimeException | Error throwable) {
                SparrowUI.getInstance().handleException("Failed to clean up replaced Window", throwable);
            }
        }
        // 撞上关服的话, 新窗当场回滚
        if (this.shutdown.get()) {
            this.active.remove(viewer.getUniqueId(), window);
            window.closeOnViewerEntity(WindowCloseReason.PLUGIN);
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }
        // 被顶替的会话自己收尾就行
        if (displaced != null) {
            displaced.endNow(WindowCloseReason.OPEN_NEW, false);
        }
        // 会话先落位, 再派发打开处理器
        if (transitionSession == null) {
            window.session(AbstractWindowSession.create(this, window));
        } else {
            transitionSession.commitOpen(window, back);
        }
        window.fireOpenHandlers();
        return Window.OpenResult.OPENED;
    }

    // 会话内的打开: 真的走到 OPENED 才算数
    boolean openInSession(AbstractWindow<?> window, AbstractWindowSession session, boolean back) {
        return this.openNow(window, session, back) == Window.OpenResult.OPENED;
    }

    // source 不在任何会话里的时候, 先让它当根窗, 这样 next 还能用 back 退回 source
    @NotNull
    CompletableFuture<Window> navigate(AbstractWindow<?> source, AbstractWindow<?> next) {
        return this.submit(
                next.viewer(),
                () -> this.navigateNow(source, next) ? next : null,
                () -> null
        );
    }

    /**
     * 等一扇还在构建中的 Window 建完, 再从出发窗打开它.
     * <p>发起的时候先记下出发窗当时挂在哪儿; 构建结果回来时出发窗已经关了, 换了位置, 或者被顶替, 这次就直接丢掉.
     *
     * @param source 上一扇 Window
     * @param next 构建中的下一扇 Window
     * @return 打开后的 Window, 打不开或出发窗已经离开原位置时以 null 完成
     */
    @NotNull
    CompletableFuture<Window> navigateLater(AbstractWindow<?> source, CompletionStage<? extends Window> next) {
        AbstractWindowSession mount = source.sessionImpl();
        return next.<Window>thenCompose(window -> {
            AbstractWindow<?> target = source.requireSameViewer(window);
            return this.submit(
                target.viewer(),
                    // 看出发窗是不是还停在发起导航时的位置: 会话归属没换过, 而且仍是那段会话的当前窗
                    () -> (source.sessionImpl() == mount && (mount == null || mount.currentWindow() == source)) && this.navigateNow(source, target)
                            ? target
                            : null,
                    () -> null
            );
        }).toCompletableFuture();
    }

    // 在玩家实体线程上看出发窗属于哪段会话, 没有就新起一段
    private boolean navigateNow(AbstractWindow<?> source, AbstractWindow<?> next) {
        AbstractWindowSession session = source.sessionImpl();
        // 还是会话成员但已经不是当前窗, 说明位置早就不在出发窗上了, 别再新起一段会话去盖它的归属
        if (session != null) {
            return session.currentWindow() == source && session.navigateNow(next);
        }

        // source 不在任何会话里就让它当根窗; 它原来的旧会话在 openNow 里照常按会话外打开收掉
        return AbstractWindowSession.create(this, source).navigateNow(next);
    }

    // 玩家实体退役时由 lane 的退役回调把整段会话收掉
    @Nullable
    SchedulerTask startTick(AbstractWindow<?> window) {
        if (this.shutdown.get()) {
            return null;
        }
        PlayerCommandLane lane = this.lane(window.viewer());
        SchedulerTask task;
        try {
            task = this.entityScheduler.runAtFixedRate(window.viewer(), window::tick, lane::retire, 1, 1);
        } catch (RuntimeException | Error throwable) {
            lane.fail(throwable);
            throw throwable;
        }
        if (task == null) {
            lane.retire();
        }
        return task;
    }

    // 关闭命令也走玩家通道串行送进去
    @NotNull
    CompletableFuture<Window.CloseResult> close(AbstractWindow<?> window) {
        boolean wasOpen = window.isOpen();
        return this.submit(
                window,
                () -> this.closeNow(window, WindowCloseReason.PLUGIN),
                () -> wasOpen ? Window.CloseResult.CLOSED : Window.CloseResult.ALREADY_CLOSED
        );
    }

    // 在玩家实体线程上关掉 Window, 并把 active 映射摘掉
    Window.CloseResult closeNow(AbstractWindow<?> window, WindowCloseReason reason) {
        if (!window.isOpen()) return Window.CloseResult.ALREADY_CLOSED;

        this.active.remove(window.viewer().getUniqueId(), window);
        boolean closed = window.closeOnViewerEntity(reason);
        this.afterCurrentWindowClosed(window, reason);
        return closed ? Window.CloseResult.CLOSED : Window.CloseResult.ALREADY_CLOSED;
    }

    // 只有正占着玩家活动窗口的那段会话, 才参与关闭之后的返回/结束决策
    private void afterCurrentWindowClosed(AbstractWindow<?> window, WindowCloseReason reason) {
        AbstractWindowSession session = window.sessionImpl();
        if (session == null || session.currentWindow() != window) {
            return;
        }
        session.onChainTopClosed(window, reason);
    }

    // 请求退回上一扇
    @NotNull
    CompletableFuture<Window> back(AbstractWindow<?> window, boolean closeAtRoot) {
        return this.submit(
                window.viewer(),
                () -> this.backNow(window, closeAtRoot),
                () -> null
        );
    }

    // 有上一扇就退回去; 没有的话看 closeAtRoot, 要么关掉要么原地不动
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
            this.closeNow(window, WindowCloseReason.PLUGIN);
        }
        return null;
    }

    // 把普通 Window 命令串到玩家的实体线程上
    @NotNull
    <T> CompletableFuture<T> submit(AbstractWindow<?> window, Callable<T> action, Callable<T> retiredAction) {
        return this.submit(window.viewer(), action, retiredAction);
    }

    // 关服之后不再排队, 直接拿 retiredAction 的结果把 Future 完成掉
    @NotNull
    <T> CompletableFuture<T> submit(Player viewer, Callable<T> action, Callable<T> retiredAction) {
        if (!this.shutdown.get()) {
            // 通道交出来的是只读阶段, 每次 toCompletableFuture 都是独立的 Future:
            // 调用方取消自己手上这个, 既动不了队列里的命令, 也影响不到别人.
            return this.lane(viewer).submit(action, retiredAction).toCompletableFuture();
        }
        try {
            return CompletableFuture.completedFuture(retiredAction.call());
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }

    /**
     * 拿玩家的命令通道, 没有就建一条并挂上退役回调.
     * <p>同一个 UUID 换了新的 Player 实例时, 旧通道先退役, 它后来的回调也不许动新通道.
     * <p>建通道的时候正好撞上关服, 就把刚建好的这条当场退役.
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
                    this.entityScheduler,
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
     * Bukkit 那边看到容器关闭时, 如果这个 InventoryView 属于某个活动 Window, 就按外部关闭处理.
     * <p>断线这种关法服务器自己会接着把容器生命周期走完, 所以必须在事件里同步通知 handler;
     * 别的原因仍旧推到下一个实体 tick, 留着"close handler 里再开一扇新窗"这条路.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void handleInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            AbstractWindow<?> window = this.active.get(player.getUniqueId());
            if (window == null || !window.ownsInventoryView(event.getView())) {
                return;
            }

            WindowCloseReason reason = WindowCloseReasonAdapter.fromBukkit(event);
            if (reason == WindowCloseReason.DISCONNECT) {
                if (this.active.remove(player.getUniqueId(), window)) {
                    try {
                        window.closeAfterInventoryEvent(WindowCloseReason.DISCONNECT);
                    } catch (RuntimeException | Error throwable) {
                        SparrowUI.getInstance().handleException("Failed to process disconnected Window close", throwable);
                    }
                    this.afterCurrentWindowClosed(window, WindowCloseReason.DISCONNECT);
                }
                return;
            }

            PlayerCommandLane lane = this.lane(window.viewer());
            lane.submitDeferred(
                    () -> {
                        this.closeNow(window, reason);
                        return null;
                    },
                    () -> null
            ).exceptionally(throwable -> {
                SparrowUI.getInstance().handleException("Failed to process external Window close", throwable);
                return null;
            });
        }
    }

    // 玩家退出事件是 DISCONNECT 那次关闭的同步兜底; 之后把这个 Player 实例的 lane 注销掉
    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        AbstractWindow<?> window = this.active.get(playerId);
        if (window != null && window.viewer() == player && this.active.remove(playerId, window)) {
            try {
                window.closeAfterInventoryEvent(WindowCloseReason.DISCONNECT);
            } catch (RuntimeException | Error throwable) {
                SparrowUI.getInstance().handleException("Failed to close Window after player quit", throwable);
            }
            this.afterCurrentWindowClosed(window, WindowCloseReason.DISCONNECT);
        }

        PlayerCommandLane lane = this.lanes.get(playerId);
        if (lane != null && lane.belongsTo(player)) {
            lane.retire();
        }
    }

    // 正常断线本该由 InventoryCloseEvent 把 Window 清掉; 这里要是还留着一扇开着的,
    // 就只回收本地状态, 并报一条 close handler 没跑到的警告
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
            // 已经没有可用的实体线程了, 只回收本地状态, 结束处理器不跑
            session.retire();
        }
        boolean wasOpen = window.retireSession();
        if (!wasOpen) {
            return;
        }
        SparrowUI.getInstance().handleException(
                "Window entity scheduler retired before close handlers ran"
                        + " [player=" + playerId
                        + ", window=" + window.getClass().getName() + "]",
                new IllegalStateException("viewer entity scheduler retired before InventoryCloseEvent")
        );
    }

    /**
     * 关掉所有活动 Window, 同时不再接新命令; 重复调用不会收尾两次.
     */
    public void shutdown() {
        if (!this.shutdown.compareAndSet(false, true)) {
            return;
        }
        // 挨个收尾还开着的 Window; 通道已经失联的就只回收本地状态
        for (AbstractWindow<?> window : Set.copyOf(this.active.values())) {
            PlayerCommandLane lane = this.lanes.get(window.viewer().getUniqueId());
            if (lane == null || !lane.belongsTo(window.viewer())) {
                this.shutdownNow(window);
                continue;
            }
            lane.terminate(() -> this.shutdownNow(window));
        }
        this.active.clear();
        // 把剩下的通道都注销掉
        for (PlayerCommandLane lane : Set.copyOf(this.lanes.values())) {
            lane.retire();
        }
        this.lanes.clear();
        // 关掉菜单后端
        if (this.menuFactory instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                SparrowUI.getInstance().handleException("Failed to close Window menu backend", exception);
            }
        }
    }

    // 在玩家的命令通道里先结束会话再关窗, 这样关闭流程不会再绕回会话的决策
    private void shutdownNow(AbstractWindow<?> window) {
        AbstractWindowSession session = window.sessionImpl();
        if (session != null) {
            try {
                session.endNow(WindowCloseReason.PLUGIN, false);
            } catch (RuntimeException | Error throwable) {
                SparrowUI.getInstance().handleException("Failed to end Window session during shutdown", throwable);
            }
        }
        try {
            this.closeNow(window, WindowCloseReason.PLUGIN);
        } catch (RuntimeException | Error throwable) {
            SparrowUI.getInstance().handleException("Failed to close Window during shutdown", throwable);
        }
    }

    /**
     * 这个玩家现在在看的那扇 Window.
     *
     * @param player 要查询的玩家
     * @return 当前 Window; 没有就是 null
     */
    @Nullable
    public Window current(@NotNull Player player) {
        return this.active.get(player.getUniqueId());
    }

    /**
     * 现在所有活动 Window, 给一份快照.
     *
     * @return 所有活动 Window
     */
    @NotNull
    @Unmodifiable
    public Set<Window> windows() {
        return Set.copyOf(this.active.values());
    }

    // 点击桥, Window 派发 Bukkit 事件时用
    BukkitInventoryBridge bukkitBridge() {
        return this.bukkitBridge;
    }

    // 建菜单的工厂
    MenuFactory menuFactory() {
        return this.menuFactory;
    }
}
