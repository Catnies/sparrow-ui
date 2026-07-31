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

/**
 * 保存每名玩家当前已提交的 Window, 并串行化该玩家的生命周期命令.
 */
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
     * 将 Window 进行开启, 这将会"打开命令"串行化提交到到玩家的实体线程.
     *
     * @param window 要打开的 Window
     * @return 打开结果阶段
     */
    @NotNull
    CompletionStage<Window.OpenResult> open(AbstractWindow<?> window) {
        return this.submit(
                window,
                () -> this.openNow(window),
                () -> Window.OpenResult.VIEWER_UNAVAILABLE
        );
    }

    /**
     * 在玩家实体线程提交打开状态.
     * 先完成新窗口初始化再发布 active 映射, 随后才关闭被替换的旧窗口.
     */
    private Window.OpenResult openNow(AbstractWindow<?> window) {
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
        window.fireOpenHandlers();
        return Window.OpenResult.OPENED;
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
     * 将 Window 进行关闭, 这将会"关闭命令"串行化提交到到玩家的实体线程.
     *
     * @param window 要关闭的 Window
     * @return 关闭结果阶段
     */
    @NotNull
    CompletionStage<Window.CloseResult> close(AbstractWindow<?> window) {
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
        return window.closeOnViewerEntity(reason)
                ? Window.CloseResult.CLOSED
                : Window.CloseResult.ALREADY_CLOSED;
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
    <T> CompletionStage<T> submit(AbstractWindow<?> window, Callable<T> action, Callable<T> retiredAction) {
        if (!this.shutdown.get()) {
            return this.lane(window.viewer()).submit(action, retiredAction);
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
     * Bukkit 观测到容器关闭时, 若 View 属于某个活动 Window 则按外部关闭处理.
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
        }

        PlayerCommandLane lane = this.lanes.get(playerId);
        if (lane != null && lane.belongsTo(player)) {
            lane.retire();
        }
    }

    // 正常断线应已由 InventoryCloseEvent 清理 Window; 若此处仍有打开 Window, 只本地注销并警告 handler 未执行.
    private void retire(UUID playerId, Player player, PlayerCommandLane lane) {
        this.lanes.remove(playerId, lane);
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
     * 返回当前已提交 Window 的快照.
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
