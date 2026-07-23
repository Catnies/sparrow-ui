package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.exception.ViewerUnavailableException;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
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

    /**
     * 返回 SparrowUI 当前启用实例持有的 WindowManager.
     *
     * @return 当前 WindowManager
     */
    public static @NotNull WindowManager getInstance() {
        return SparrowUI.getInstance().windowManager();
    }

    /**
     * 创建并注册使用 Paper 菜单后端的 WindowManager.
     *
     * @return 已注册的 WindowManager
     */
    public static @NotNull WindowManager create() {
        WindowManager manager = new WindowManager(new MenuFactoryImpl(SparrowUI.getInstance().getPlugin()));
        Bukkit.getPluginManager().registerEvents(manager, SparrowUI.getInstance().getPlugin());
        return manager;
    }

    /**
     * 返回该玩家当前已提交的 Window.
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

    @NotNull CompletionStage<Window.OpenResult> open(AbstractWindow<?> window) {
        if (this.shutdown.get()) {
            window.retire();
            this.active.remove(window.viewer().getUniqueId(), window);
            return CompletableFuture.completedFuture(Window.OpenResult.VIEWER_UNAVAILABLE);
        }
        return this.lane(window.viewer()).submit(
                () -> this.openNow(window),
                () -> {
                    window.retire();
                    this.active.remove(window.viewer().getUniqueId(), window);
                    return Window.OpenResult.VIEWER_UNAVAILABLE;
                }
        );
    }

    @NotNull CompletionStage<Window.CloseResult> close(AbstractWindow<?> window) {
        if (this.shutdown.get()) {
            boolean wasOpen = window.retire();
            this.active.remove(window.viewer().getUniqueId(), window);
            return CompletableFuture.completedFuture(
                    wasOpen ? Window.CloseResult.CLOSED : Window.CloseResult.ALREADY_CLOSED
            );
        }
        return this.lane(window.viewer()).submit(
                () -> this.closeNow(window, InventoryCloseEvent.Reason.PLUGIN, MenuHandle.CloseMode.PLUGIN),
                () -> {
                    boolean wasOpen = window.retire();
                    this.active.remove(window.viewer().getUniqueId(), window);
                    return wasOpen ? Window.CloseResult.CLOSED : Window.CloseResult.ALREADY_CLOSED;
                }
        );
    }

    /**
     * 将 Window 状态修改送入其玩家命令通道, 使公开 setter 可从任意线程调用.
     */
    void mutate(AbstractWindow<?> window, Runnable mutation, String failureMessage) {
        if (this.shutdown.get()) {
            return;
        }
        this.observe(this.lane(window.viewer()).submit(() -> {
            mutation.run();
            return null;
        }, () -> null), failureMessage);
    }

    @Nullable
    SchedulerTask startTick(AbstractWindow<?> window) {
        if (this.shutdown.get()) {
            return null;
        }
        PlayerCommandLane lane = this.lane(window.viewer());
        return SparrowUI.getInstance().scheduler().entity().runAtFixedRate(window.viewer(), window::tick, lane::retire, 1, 1);
    }

    MenuFactory menuFactory() {
        return this.menuFactory;
    }

    void closeFromClient(AbstractWindow<?> window, InventoryCloseEvent.Reason reason) {
        this.closeNow(window, reason, MenuHandle.CloseMode.CLIENT);
    }

    void closeAfterProtocolFailure(AbstractWindow<?> window) {
        try {
            this.closeNow(window, InventoryCloseEvent.Reason.UNKNOWN, MenuHandle.CloseMode.PLUGIN);
        } catch (RuntimeException | Error throwable) {
            this.report("Failed to close Window after a protocol failure", throwable);
        }
    }

    /**
     * 延后处理 Bukkit 观测到的外部关闭.
     * 不在 InventoryCloseEvent 调用栈中递归操作菜单, 以避免与原生关闭流程冲突.
     */
    void externalClose(AbstractWindow<?> window, InventoryCloseEvent.Reason reason) {
        this.observe(this.lane(window.viewer()).submitDeferred(() -> {
            if (!window.isOpen()) {
                return null;
            }
            this.closeNow(window, reason, MenuHandle.CloseMode.CLIENT);
            return null;
        }, () -> {
            window.retire();
            return null;
        }), "Failed to process external Window close");
    }

    /**
     * 在启用桥接时把已映射的协议点击发布为 Bukkit InventoryClickEvent.
     * Bukkit 事件取消或桥接异常都会拒绝该次 Window 点击.
     */
    boolean allowClick(AbstractWindow<?> window, ClickInterpreter.SingleClick click) {
        if (!SparrowUI.getInstance().isFireBukkitInventoryEvents()) {
            return true;
        }
        InventoryView view = window.menuView();
        int rawSlot = switch (click.target()) {
            case ClickInterpreter.GuiTarget target -> target.windowSlot();
            case ClickInterpreter.PlayerTarget target -> target.windowSlot();
            case ClickInterpreter.OutsideTarget ignoredTarget -> InventoryView.OUTSIDE;
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
     * 在启用桥接时把已完成的 QUICK_CRAFT 手势发布为 Bukkit InventoryDragEvent.
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

    void report(String message, Throwable throwable) {
        this.exceptionHandler.accept(message, throwable);
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

    @EventHandler(priority = EventPriority.HIGHEST)
    private void handleInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        AbstractWindow<?> window = this.active.get(player.getUniqueId());
        if (window != null && window.owns(event.getView())) {
            window.externalClose(event.getReason());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        AbstractWindow<?> window = this.active.get(event.getPlayer().getUniqueId());
        if (window == null) {
            return;
        }
        this.observe(this.lane(event.getPlayer()).submit(() -> {
            this.closeNow(window, InventoryCloseEvent.Reason.DISCONNECT, MenuHandle.CloseMode.CLIENT);
            return null;
        }, () -> {
            window.retire();
            return null;
        }), "Failed to close Window after player quit");
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
        try {
            window.openOnEntity(this.generations.getAndIncrement());
        } catch (ViewerUnavailableException ignored) {
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }

        this.active.put(viewer.getUniqueId(), window);
        if (this.shutdown.get()) {
            this.active.remove(viewer.getUniqueId(), window);
            window.closeOnEntity(InventoryCloseEvent.Reason.PLUGIN, MenuHandle.CloseMode.PLUGIN);
            return Window.OpenResult.VIEWER_UNAVAILABLE;
        }

        if (previous != null && previous != window) {
            try {
                previous.closeOnEntity(InventoryCloseEvent.Reason.OPEN_NEW, MenuHandle.CloseMode.REPLACED);
            } catch (RuntimeException | Error throwable) {
                this.report("Failed to clean up replaced Window", throwable);
            }
        }
        window.fireOpenHandlers();
        return Window.OpenResult.OPENED;
    }

    /**
     * 在玩家实体线程关闭 Window 并先移除 active 映射.
     * 该顺序允许关闭回调或 fallback 立即打开新的 Window.
     */
    private Window.CloseResult closeNow(AbstractWindow<?> window, InventoryCloseEvent.Reason reason, MenuHandle.CloseMode mode) {
        if (!window.isOpen()) {
            return Window.CloseResult.ALREADY_CLOSED;
        }
        this.active.remove(window.viewer().getUniqueId(), window);
        window.closeOnEntity(reason, mode);
        return Window.CloseResult.CLOSED;
    }

    private PlayerCommandLane lane(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerCommandLane lane = this.lanes.computeIfAbsent(playerId, ignoredPlayerId -> new PlayerCommandLane(player, () -> this.retire(playerId)));
        if (this.shutdown.get() && this.lanes.remove(playerId, lane)) {
            lane.retire();
        }
        return lane;
    }

    private void retire(UUID playerId) {
        this.lanes.remove(playerId);
        AbstractWindow<?> window = this.active.remove(playerId);
        if (window != null) {
            window.retire();
        }
    }

    private void observe(CompletionStage<?> stage, String message) {
        stage.exceptionally(throwable -> {
            this.report(message, throwable);
            return null;
        });
    }
}
