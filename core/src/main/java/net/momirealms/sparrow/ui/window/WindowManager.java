package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.exception.ViewerUnavailableException;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuFactoryImpl;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;
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
    private final BiConsumer<? super String, ? super Throwable> exceptionHandler;
    private final Map<UUID, AbstractWindow<?>> active = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerCommandLane> lanes = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final AtomicBoolean shutdown = new AtomicBoolean();

    WindowManager(MenuFactory menuFactory) {
        this.menuFactory = menuFactory;
        this.exceptionHandler = SparrowUI.getInstance()::handleException;
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
        WindowManager manager = new WindowManager(new MenuFactoryImpl(SparrowUI.getInstance().getPlugin()));
        Bukkit.getPluginManager().registerEvents(manager, SparrowUI.getInstance().getPlugin());
        return manager;
    }

    /**
     * 返回该玩家当前真正观察的 Window.
     *
     * @param player 要查询的玩家
     * @return 当前 Window, 没有时为 null
     */
    @Nullable
    public Window current(@NotNull Player player) {
        return this.active.get(player.getUniqueId());
    }

    /**
     * 返回当前已提交 Window 的不可修改快照.
     *
     * @return 所有活动 Window
     */
    @NotNull
    @Unmodifiable
    public Set<Window> windows() {
        return Set.copyOf(this.active.values());
    }

    /**
     * 在插件禁用时退役所有本地资源并移除连接 handler.
     */
    public void shutdown() {
        if (!this.shutdown.compareAndSet(false, true)) {
            return;
        }
        for (AbstractWindow<?> window : Set.copyOf(this.active.values())) {
            window.retire();
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
     * 返回创建协议菜单的工厂.
     *
     * @return 菜单工厂
     */
    MenuFactory menuFactory() {
        return this.menuFactory;
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
                () -> {
                    window.retire();
                    this.active.remove(window.viewer().getUniqueId(), window);
                    return Window.OpenResult.VIEWER_UNAVAILABLE;
                }
        );
    }

    /**
     * 将 Window 进行关闭, 这将会"关闭命令"串行化提交到到玩家的实体线程.
     *
     * @param window 要关闭的 Window
     * @return 关闭结果阶段
     */
    @NotNull
    CompletionStage<Window.CloseResult> close(AbstractWindow<?> window) {
        return this.submit(
                window,
                () -> this.closeNow(window, InventoryCloseEvent.Reason.PLUGIN),
                () -> {
                    boolean wasOpen = window.retire();
                    this.active.remove(window.viewer().getUniqueId(), window);
                    return wasOpen ? Window.CloseResult.CLOSED : Window.CloseResult.ALREADY_CLOSED;
                }
        );
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
     * 为 Window 启动玩家的实体线程上的周期 tick.
     * 实体退役时通过 lane 的退役回调回收整个会话.
     *
     * @param window 要 tick 的 Window
     * @return tick 任务, shutdown 后为 null
     */
    @Nullable
    SchedulerTask startTick(AbstractWindow<?> window) {
        if (this.shutdown.get()) {
            return null;
        }
        PlayerCommandLane lane = this.lane(window.viewer());
        return SparrowUI.getInstance().scheduler().entity().runAtFixedRate(window.viewer(), window::tick, lane::retire, 1, 1);
    }

    /**
     * 在启用Bukkit事件桥接时, 把已映射的协议点击发布为 Bukkit InventoryClickEvent.
     * Bukkit 事件取消或桥接异常都会拒绝该次 Window 点击.
     */
    boolean allowClick(AbstractWindow<?> window, ClickInterpreter.Result.SingleClick click) {
        if (!SparrowUI.getInstance().isFireBukkitInventoryEvents()) {
            return true;
        }
        InventoryView view = window.menuView();
        int rawSlot = switch (click.target()) {
            case ClickInterpreter.Target.GuiTarget target -> target.windowSlot();
            case ClickInterpreter.Target.PlayerTarget target -> target.windowSlot();
            case ClickInterpreter.Target.OutsideTarget ignoredTarget -> InventoryView.OUTSIDE;
        };
        InventoryType.SlotType slotType = rawSlot == InventoryView.OUTSIDE
                ? InventoryType.SlotType.OUTSIDE
                : view.getSlotType(rawSlot);
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                slotType,
                rawSlot,
                click.clickType(),
                InventoryAction.UNKNOWN,
                click.hotbarButton()
        );
        try {
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        } catch (Throwable throwable) {
            this.report("Failed to bridge Window click to Bukkit", throwable);
            return false;
        }
    }

    /**
     * 在启用Bukkit事件桥接时, 把已完成的 QUICK_CRAFT 手势发布为 Bukkit InventoryDragEvent.
     * Bukkit 事件取消或桥接异常都会拒绝该次 Window 点击.
     */
    boolean allowDrag(AbstractWindow<?> window, ClickType clickType, List<Integer> slots) {
        if (!SparrowUI.getInstance().isFireBukkitInventoryEvents()) {
            return true;
        }
        InventoryView view = window.menuView();
        ItemStack oldCursor = view.getCursor();
        LinkedHashMap<Integer, ItemStack> results = new LinkedHashMap<>();
        for (int index = 0; index < slots.size(); index++) {
            int rawSlot = slots.get(index);
            ItemStack current = view.getItem(rawSlot);
            results.put(rawSlot, current == null ? ItemStack.empty() : current);
        }
        InventoryDragEvent event = new InventoryDragEvent(
                view,
                oldCursor.clone(),
                oldCursor,
                clickType == ClickType.RIGHT,
                results
        );
        try {
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        } catch (Throwable throwable) {
            this.report("Failed to bridge Window drag to Bukkit", throwable);
            return false;
        }
    }

    /**
     * 将内部异常交给插件的统一异常处理器.
     *
     * @param message 错误说明
     * @param throwable 异常
     */
    void report(String message, Throwable throwable) {
        this.exceptionHandler.accept(message, throwable);
    }

    /**
     * 在玩家实体线程关闭 Window 并先移除 active 映射.
     * 该顺序允许关闭回调或 fallback 立即打开新的 Window.
     */
    Window.CloseResult closeNow(AbstractWindow<?> window, InventoryCloseEvent.Reason reason) {
        if (!window.isOpen()) {
            return Window.CloseResult.ALREADY_CLOSED;
        }
        this.active.remove(window.viewer().getUniqueId(), window);
        window.closeOnEntity(reason);
        return Window.CloseResult.CLOSED;
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
            window.openOnEntity(this.generations.getAndIncrement(), replaceWindow);
        } catch (ViewerUnavailableException ignored) {
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }

        this.active.put(viewer.getUniqueId(), window);
        if (replaceWindow) {
            try {
                previous.closeOnEntity(InventoryCloseEvent.Reason.OPEN_NEW);
            } catch (RuntimeException | Error throwable) {
                this.report("Failed to clean up replaced Window", throwable);
            }
        }
        if (this.shutdown.get()) {
            this.active.remove(viewer.getUniqueId(), window);
            window.closeOnEntity(InventoryCloseEvent.Reason.PLUGIN);
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }
        window.fireOpenHandlers();
        return Window.OpenResult.OPENED;
    }

    /**
     * 返回玩家的命令通道, 不存在时创建并注册退役回调.
     * Shutdown 与新通道创建竞争时, 立即退役刚创建的通道.
     *
     * @param player 玩家
     * @return 玩家的命令通道
     */
    private PlayerCommandLane lane(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerCommandLane lane = this.lanes.computeIfAbsent(playerId, ignoredPlayerId -> new PlayerCommandLane(player, () -> this.retire(playerId)));
        if (this.shutdown.get() && this.lanes.remove(playerId, lane)) {
            lane.retire();
        }
        return lane;
    }

    /**
     * 玩家实体退役时回收其命令通道与活动 Window.
     *
     * @param playerId 玩家 id
     */
    private void retire(UUID playerId) {
        this.lanes.remove(playerId);
        AbstractWindow<?> window = this.active.remove(playerId);
        if (window != null) {
            window.retire();
        }
    }

    /**
     * 统一上报异步命令的失败.
     *
     * @param stage 命令阶段
     * @param message 失败报告文本
     */
    private void observe(CompletionStage<?> stage, String message) {
        stage.exceptionally(throwable -> {
            this.report(message, throwable);
            return null;
        });
    }

    /**
     * Bukkit 观测到容器关闭时, 若 View 属于某个活动 Window 则按外部关闭处理.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void handleInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            AbstractWindow<?> window = this.active.get(player.getUniqueId());
            if (window != null && window.owns(event.getView())) {
                PlayerCommandLane lane = this.lane(window.viewer());
                this.observe(
                        lane.submitDeferred(
                                () -> {
                                    if (!window.isOpen()) {
                                        return null;
                                    }
                                    this.closeNow(window, event.getReason());
                                    return null;
                                },
                                () -> {
                                    window.retire();
                                    return null;
                                }
                        ),
                        "Failed to process external Window close"
                );
            }
        }
    }

    /**
     * 玩家退出时在其实体线程按 DISCONNECT 关闭活动 Window, 无法调度则直接退役.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        AbstractWindow<?> window = this.active.get(event.getPlayer().getUniqueId());
        if (window == null) {
            return;
        }
        this.observe(
                this.submit(
                        window,
                        () -> {
                            this.closeNow(window, InventoryCloseEvent.Reason.DISCONNECT);
                            return null;
                        },
                        () -> {
                            window.retire();
                            return null;
                        }
                ),
                "Failed to close Window after player quit"
        );
    }
}
