package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.exception.ViewerUnavailableException;
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
 * 按玩家串行执行 Window 命令, 并维护活动窗口与会话.
 */
public final class WindowManager implements Listener {
    private final MenuFactory menuFactory;
    private final WindowScheduler scheduler;
    private final BukkitInventoryBridge bukkitBridge;
    private final Map<UUID, AbstractWindow<?>> active = new ConcurrentHashMap<>(); // 玩家 -> 当前窗
    private final Map<UUID, PlayerCommandLane> lanes = new ConcurrentHashMap<>();  // 玩家 -> 命令通道
    private final AtomicLong generations = new AtomicLong();                   // 打开代际, 隔离迟到输入
    private final AtomicBoolean shutdown = new AtomicBoolean();                // 是否已进入关服收尾

    WindowManager(Plugin plugin) {
        this(plugin, new MenuFactoryImpl(plugin));
    }

    WindowManager(Plugin plugin, MenuFactory menuFactory) {
        this.menuFactory = menuFactory;
        this.scheduler = new WindowScheduler(plugin);
        this.bukkitBridge = new BukkitInventoryBridge();
    }

    @NotNull
    public static WindowManager getInstance() {
        return SparrowUI.getInstance().windowManager();
    }

    @NotNull
    @ApiStatus.Internal
    public static WindowManager create() {
        Plugin plugin = SparrowUI.getInstance().getPlugin();
        WindowManager manager = new WindowManager(plugin);
        Bukkit.getPluginManager().registerEvents(manager, plugin);
        return manager;
    }

    // 打开命令经玩家通道串行送入实体线程.
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
        // 校验关服与窗口可用性
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

        // 推导会话归属, 同会话内交接不算被顶替
        AbstractWindow<?> previous = this.active.get(viewer.getUniqueId());
        AbstractWindowSession displaced = previous == null ? null : previous.sessionImpl();
        if (displaced == transitionSession) {
            displaced = null;
        }
        boolean replaceWindow = previous != null && previous != window;
        // 在实体线程初始化新窗口
        try {
            window.openOnViewerEntity(this.generations.getAndIncrement(), replaceWindow);
        } catch (ViewerUnavailableException ignored) {
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }

        // 发布活动窗并清理被替换的旧窗
        this.active.put(viewer.getUniqueId(), window);
        if (replaceWindow) {
            try {
                previous.closeOnViewerEntity(InventoryCloseEvent.Reason.OPEN_NEW);
            } catch (RuntimeException | Error throwable) {
                SparrowUI.getInstance().handleException("Failed to clean up replaced Window", throwable);
            }
        }
        // 关服竞态, 新窗立即回滚
        if (this.shutdown.get()) {
            this.active.remove(viewer.getUniqueId(), window);
            window.closeOnViewerEntity(InventoryCloseEvent.Reason.PLUGIN);
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }
        // 被顶替的会话只做自身收尾
        if (displaced != null) {
            displaced.endNow(InventoryCloseEvent.Reason.OPEN_NEW, false);
        }
        // 会话落位, 再触发打开回调
        if (transitionSession == null) {
            window.session(AbstractWindowSession.create(this, window));
        } else {
            transitionSession.commitOpen(window, back);
        }
        window.fireOpenHandlers();
        return Window.OpenResult.OPENED;
    }

    boolean openInSession(AbstractWindow<?> window, AbstractWindowSession session, boolean back) {
        return this.openNow(window, session, back) == Window.OpenResult.OPENED;
    }

    // source 不在会话中时先成为根窗, next 仍可经 back 返回 source.
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
     * <p>发起时先记下出发窗当时的挂载. 构建结果到达时若出发窗已关闭/已变更/被顶替就丢弃.
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
        // 仍是会话成员却已不在当前位置, 位置早就不在出发窗上, 不再新起一段会话去覆盖它的归属
        if (session != null) {
            return session.currentWindow() == source && session.navigateNow(next);
        }

    // source 不在任何会话中就让它当根窗, 旧会话在 openNow 里照常按会话外打开结束
        return AbstractWindowSession.create(this, source).navigateNow(next);
    }

    // 实体退役时由 lane 的退役回调回收整段会话.
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

    // 关闭命令经玩家通道串行送入实体线程.
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

    // 只有正占用玩家活动窗口的会话参与关闭后的返回或结束决策.
    private void afterCurrentWindowClosed(AbstractWindow<?> window, InventoryCloseEvent.Reason reason) {
        AbstractWindowSession session = window.sessionImpl();
        if (session == null || session.currentWindow() != window) {
            return;
        }
        session.onChainTopClosed(window, reason);
    }

    // 请求回到上一扇
    @NotNull
    CompletableFuture<Window> back(AbstractWindow<?> window, boolean closeAtRoot) {
        return this.submit(
                window.viewer(),
                () -> this.backNow(window, closeAtRoot),
                () -> null
        );
    }

    // 有上一扇时返回, 其余情况按 closeAtRoot 决定关闭还是保持原样.
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

    // 将普通 Window 命令串行化到玩家的实体线程.
    @NotNull
    <T> CompletableFuture<T> submit(AbstractWindow<?> window, Callable<T> action, Callable<T> retiredAction) {
        return this.submit(window.viewer(), action, retiredAction);
    }

    // shutdown 后不再调度, 直接以 retiredAction 的结果完成.
    @NotNull
    <T> CompletableFuture<T> submit(Player viewer, Callable<T> action, Callable<T> retiredAction) {
        if (!this.shutdown.get()) {
            // 通道给的是只读阶段, toCompletableFuture 每次生成独立 Future.
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
     * Bukkit 观测到容器关闭时, 若 InventoryView 属于某个活动 Window 则按外部关闭处理.
     * 断线关闭已经由服务器接管, 事件返回后会继续完成容器生命周期, 因此必须在事件内同步通知 handler.
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
                        SparrowUI.getInstance().handleException("Failed to process disconnected Window close", throwable);
                    }
                    this.afterCurrentWindowClosed(window, InventoryCloseEvent.Reason.DISCONNECT);
                }
                return;
            }

            PlayerCommandLane lane = this.lane(window.viewer());
            lane.submitDeferred(
                    () -> {
                        this.closeNow(window, event.getReason());
                        return null;
                    },
                    () -> null
            ).exceptionally(throwable -> {
                SparrowUI.getInstance().handleException("Failed to process external Window close", throwable);
                return null;
            });
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
                SparrowUI.getInstance().handleException("Failed to close Window after player quit", throwable);
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
        SparrowUI.getInstance().handleException(
                "Window entity scheduler retired before close handlers ran"
                        + " [player=" + playerId
                        + ", window=" + window.getClass().getName() + "]",
                new IllegalStateException("viewer entity scheduler retired before InventoryCloseEvent")
        );
    }

    /**
     * 关闭所有活动 Window 并停止接收新命令, 重复调用不会再次执行收尾.
     */
    public void shutdown() {
        if (!this.shutdown.compareAndSet(false, true)) {
            return;
        }
        // 逐个收尾正在开启的 Window, 已失联的通道只回收本地状态
        for (AbstractWindow<?> window : Set.copyOf(this.active.values())) {
            PlayerCommandLane lane = this.lanes.get(window.viewer().getUniqueId());
            if (lane == null || !lane.belongsTo(window.viewer())) {
                this.shutdownNow(window);
                continue;
            }
            lane.terminate(() -> this.shutdownNow(window));
        }
        this.active.clear();
        // 注销剩余通道
        for (PlayerCommandLane lane : Set.copyOf(this.lanes.values())) {
            lane.retire();
        }
        this.lanes.clear();
        // 关闭菜单后端
        if (this.menuFactory instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                SparrowUI.getInstance().handleException("Failed to close Window menu backend", exception);
            }
        }
    }

    // 在玩家命令通道内先结束会话再关闭 Window, 关闭流程不会重新进入会话决策.
    private void shutdownNow(AbstractWindow<?> window) {
        AbstractWindowSession session = window.sessionImpl();
        if (session != null) {
            try {
                session.endNow(InventoryCloseEvent.Reason.PLUGIN, false);
            } catch (RuntimeException | Error throwable) {
                SparrowUI.getInstance().handleException("Failed to end Window session during shutdown", throwable);
            }
        }
        try {
            this.closeNow(window, InventoryCloseEvent.Reason.PLUGIN);
        } catch (RuntimeException | Error throwable) {
            SparrowUI.getInstance().handleException("Failed to close Window during shutdown", throwable);
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

    BukkitInventoryBridge bukkitBridge() {
        return this.bukkitBridge;
    }

    MenuFactory menuFactory() {
        return this.menuFactory;
    }
}
