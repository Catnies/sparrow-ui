package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.BundleSelect;
import net.momirealms.sparrow.ui.ClickEvent;
import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.exception.ViewerUnavailableException;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.SlotElement;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.inventory.ClickSemantics;
import net.momirealms.sparrow.ui.inventory.Inventory;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.MiscUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 各类 Window 共用的生命周期、槽位路由和协议同步引擎.
 * <p>公开状态通过 volatile 快照提供跨线程读取, 菜单、路径和容器状态只在玩家的实体线程访问.
 * 每次打开都会取得新的 generation, 以隔离迟到的协议输入和异步失效通知.
 */
abstract class AbstractWindow<M extends MenuHandle> implements Window {
    /**
     * Builder 交给共享生命周期构造器的不可变设置快照.
     *
     * <p>此类型只保存所有 Window 都具备的行为设置, 不描述菜单协议或槽位布局.</p>
     *
     * @param titleSupplier 动态标题来源
     * @param closeable 是否接受客户端主动关闭
     * @param openHandlers 打开处理器
     * @param closeHandlers 关闭处理器
     * @param outsideClickHandlers 容器外点击处理器
     * @param fallbackWindow 玩家关闭后的后备 Window 来源
     * @param windowState 初始服务器 Window 状态
     * @param windowStateChangeHandlers 客户端状态确认处理器
     * @param cursorVisualizer 光标显示转换器
     */
    record Settings(
            @NotNull Supplier<? extends Component> titleSupplier,
            boolean closeable,
            @NotNull List<Runnable> openHandlers,
            @NotNull List<Consumer<InventoryCloseEvent.Reason>> closeHandlers,
            @NotNull List<Consumer<ClickEvent>> outsideClickHandlers,
            @NotNull Supplier<? extends @Nullable Window> fallbackWindow,
            int windowState,
            @NotNull List<Consumer<Integer>> windowStateChangeHandlers,
            @NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer
    ) {
    }

    private static final int INCOMING_PER_TICK = 128;
    private static final int PLAYER_INVENTORY_AUDIT_INTERVAL = 20;
    private static final long PING_TIMEOUT_MILLIS = 30_000;
    private static final BitSet EMPTY_DIRTY_SLOTS = new BitSet(); // TODO: 优化点, 防止泄露后被意外修改.

    private final WindowManager manager;
    private final Player viewer;
    private final WindowLayout layout;
    private final Object dirtyLock = new Object();
    private final ClickInterpreter clickInterpreter = new ClickInterpreter();
    private final ClickSemantics.Context semanticsContext = new SemanticsContext(); // 点击语义引擎的槽位路由与玩家侧 IO
    private final RenderContext cursorRenderContext;
    private final Map<Integer, PendingWindowState> pendingWindowStates = new HashMap<>();
    private final HandlerList<Runnable> openHandlers;
    private final HandlerList<Consumer<InventoryCloseEvent.Reason>> closeHandlers;
    private final HandlerList<Consumer<ClickEvent>> outsideClickHandlers;
    private final HandlerList<Consumer<Integer>> windowStateChangeHandlers;

    private volatile Component title;
    private volatile Supplier<? extends Component> titleSupplier;
    private volatile boolean closeable;
    private volatile boolean open;
    private volatile long generation;
    private volatile Supplier<? extends @Nullable Window> fallbackWindow;
    private volatile int serverWindowState;
    private volatile int clientWindowState;
    private volatile Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer;

    private @Nullable M menuHandle;
    private @Nullable DisplayedSlotPath[] paths;
    private @Nullable ItemStack[] localSlots;
    private @Nullable MenuHandle.CursorSnapshot localCursor;
    private @Nullable SchedulerTask tickTask;
    private BitSet dirtySlots;
    private BitSet spareDirtySlots;
    private long windowTick;
    private int playerInventoryVersion;
    private boolean cursorDirty;
    private boolean forceFull;
    private boolean menuDirty;
    private boolean titleDirty;

    AbstractWindow(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull Settings settings
    ) {
        this.manager = manager;
        this.viewer = viewer;
        this.layout = layout;
        this.title = Component.empty();
        this.titleSupplier = settings.titleSupplier();
        this.closeable = settings.closeable();
        this.openHandlers = new HandlerList<>(settings.openHandlers());
        this.closeHandlers = new HandlerList<>(settings.closeHandlers());
        this.outsideClickHandlers = new HandlerList<>(settings.outsideClickHandlers());
        this.fallbackWindow = settings.fallbackWindow();
        this.serverWindowState = settings.windowState();
        this.windowStateChangeHandlers = new HandlerList<>(settings.windowStateChangeHandlers());
        this.cursorVisualizer = settings.cursorVisualizer();
        this.dirtySlots = new BitSet(layout.size());
        this.spareDirtySlots = new BitSet(layout.size());
        this.cursorRenderContext = RenderContext.cursor(this);
    }

    @NotNull
    @Override
    public CompletionStage<OpenResult> open() {
        return this.manager.open(this);
    }

    @NotNull
    @Override
    public CompletionStage<CloseResult> close() {
        return this.manager.close(this);
    }

    @Override
    public void setTitleSupplier(@NotNull Supplier<? extends Component> titleSupplier) {
        Objects.requireNonNull(titleSupplier, "titleSupplier");
        this.submit(
                () -> {
                    this.titleSupplier = titleSupplier;
                    this.notifyUpdateTitle();
                },
                "Failed to update Window title supplier"
        );
    }

    @Override
    public void setTitle(@NotNull Component title) {
        Objects.requireNonNull(title, "title");
        this.submit(
                () -> {
                    this.titleSupplier = () -> title;
                    this.notifyUpdateTitle(title);
                },
                "Failed to update Window title"
        );
    }

    @Override
    public void updateTitle() {
        this.submit(this::notifyUpdateTitle, "Failed to refresh Window title");
    }

    @NotNull
    @Override
    public Component title() {
        return this.title;
    }

    @Override
    public void setCloseable(boolean closeable) {
        this.submit(
                () -> this.closeable = closeable,
                "Failed to update Window closeable state"
        );
    }

    @Override
    public void setFallbackWindow(@NotNull Supplier<? extends @Nullable Window> fallbackWindow) {
        Objects.requireNonNull(fallbackWindow, "fallbackWindow");
        this.submit(
                () -> this.fallbackWindow = fallbackWindow,
                "Failed to update Window fallback"
        );
    }

    @Override
    public void setOpenHandlers(@NotNull List<? extends Runnable> openHandlers) {
        List<Runnable> copy = List.copyOf(openHandlers);
        this.submit(() -> this.openHandlers.set(copy), "Failed to replace Window open handlers");
    }

    @NotNull
    @Override
    public List<Runnable> getOpenHandlers() {
        return this.openHandlers.snapshot();
    }

    @Override
    public void addOpenHandler(@NotNull Runnable openHandler) {
        this.submit(
                () -> this.openHandlers.append(openHandler),
                "Failed to add Window open handler"
        );
    }

    @Override
    public void removeOpenHandler(@NotNull Runnable openHandler) {
        this.submit(
                () -> this.openHandlers.remove(openHandler),
                "Failed to remove Window open handler"
        );
    }

    @Override
    public void setCloseHandlers(@NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> closeHandlers) {
        List<Consumer<InventoryCloseEvent.Reason>> copy = MiscUtils.copyConsumers(closeHandlers);
        this.submit(
                () -> this.closeHandlers.set(copy),
                "Failed to replace Window close handlers"
        );
    }

    @NotNull
    @Override
    public List<Consumer<InventoryCloseEvent.Reason>> getCloseHandlers() {
        return this.closeHandlers.snapshot();
    }

    @Override
    public void addCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler) {
        Consumer<InventoryCloseEvent.Reason> handler = MiscUtils.narrowConsumer(closeHandler);
        this.submit(
                () -> this.closeHandlers.append(handler),
                "Failed to add Window close handler"
        );
    }

    @Override
    public void removeCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler) {
        this.submit(
                () -> this.closeHandlers.remove(MiscUtils.narrowConsumer(closeHandler)),
                "Failed to remove Window close handler"
        );
    }

    @Override
    public void setOutsideClickHandlers(@NotNull List<? extends Consumer<? super ClickEvent>> outsideClickHandlers) {
        List<Consumer<ClickEvent>> copy = MiscUtils.copyConsumers(outsideClickHandlers);
        this.submit(
                () -> this.outsideClickHandlers.set(copy),
                "Failed to replace Window outside click handlers"
        );
    }

    @NotNull
    @Override
    public List<Consumer<ClickEvent>> getOutsideClickHandlers() {
        return this.outsideClickHandlers.snapshot();
    }

    @Override
    public void addOutsideClickHandler(@NotNull Consumer<? super ClickEvent> outsideClickHandler) {
        Consumer<ClickEvent> handler = MiscUtils.narrowConsumer(outsideClickHandler);
        this.submit(
                () -> this.outsideClickHandlers.append(handler),
                "Failed to add Window outside click handler"
        );
    }

    @Override
    public void removeOutsideClickHandler(@NotNull Consumer<? super ClickEvent> outsideClickHandler) {
        this.submit(
                () -> this.outsideClickHandlers.remove(MiscUtils.narrowConsumer(outsideClickHandler)),
                "Failed to remove Window outside click handler"
        );
    }

    @Override
    public void setWindowState(int windowState) {
        this.submit(
                () -> this.updateWindowStateOnEntity(windowState),
                "Failed to update Window state"
        );
    }

    @Override
    public void incrementWindowState() {
        this.submit(
                () -> this.updateWindowStateOnEntity(this.serverWindowState + 1),
                "Failed to increment Window state"
        );
    }

    @Override
    public int getServerWindowState() {
        return this.serverWindowState;
    }

    @Override
    public int getClientWindowState() {
        return this.clientWindowState;
    }

    @Override
    public void setWindowStateChangeHandlers(@NotNull List<? extends Consumer<? super Integer>> handlers) {
        List<Consumer<Integer>> copy = MiscUtils.copyConsumers(handlers);
        this.submit(
                () -> this.windowStateChangeHandlers.set(copy),
                "Failed to replace Window state handlers"
        );
    }

    @NotNull
    @Override
    public List<Consumer<Integer>> getWindowStateChangeHandlers() {
        return this.windowStateChangeHandlers.snapshot();
    }

    @Override
    public void addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        Consumer<Integer> copied = MiscUtils.narrowConsumer(handler);
        this.submit(
                () -> this.windowStateChangeHandlers.append(copied),
                "Failed to add Window state handler"
        );
    }

    @Override
    public void removeWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        this.submit(
                () -> this.windowStateChangeHandlers.remove(MiscUtils.narrowConsumer(handler)),
                "Failed to remove Window state handler"
        );
    }

    @Override
    public void setCursorVisualizer(@NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer) {
        Objects.requireNonNull(cursorVisualizer, "cursorVisualizer");
        this.submit(
                () -> {
                    this.cursorVisualizer = cursorVisualizer;
                    this.cursorDirty = true;
                },
                "Failed to update Window cursor visualizer"
        );
    }

    @NotNull
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> getCursorVisualizer() {
        return this.cursorVisualizer;
    }

    @Override
    public void notifyUpdate(int windowSlot) {
        if (windowSlot < 0 || windowSlot >= this.layout.size()) {
            return;
        }
        synchronized (this.dirtyLock) {
            this.dirtySlots.set(windowSlot);
        }
    }

    @Override
    public void notifyUpdateAll() {
        this.submit(
                () -> {
                    this.cursorDirty = true;
                    this.forceFull = true;
                    this.flush(false);
                },
                "Failed to resend Window data"
        );
    }

    @NotNull
    @Override
    public Player viewer() {
        return this.viewer;
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    public boolean isCloseable() {
        return this.closeable;
    }

    @NotNull
    @Override
    public List<Gui> guis() {
        return this.layout.guis();
    }

    @Nullable
    @Override
    public SlotElement.GuiLink guiAt(int windowSlot) {
        return this.layout.guiAt(windowSlot);
    }

    @Nullable
    @Override
    public SlotElement.GuiLink guiAtHotbar(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            throw new IndexOutOfBoundsException("hotbar slot out of bounds: " + hotbarSlot);
        }
        return this.layout.guiAt(this.layout.windowSlotAtHotbar(hotbarSlot));
    }

    /**
     * 在玩家的实体线程创建菜单、显示路径和初始协议状态.
     * 所有资源在初始完整包成功排入 Netty event loop 后才发布到字段, 失败时按相反方向回滚.
     */
    void openOnEntity(long generation, boolean replacingWindow) {
        // 初始化本次打开的 generation 相关状态
        this.generation = generation;
        this.windowTick = 0;
        this.cursorDirty = true;
        this.forceFull = true;
        this.menuDirty = true;
        this.titleDirty = false;
        this.clickInterpreter.reset();
        this.pendingWindowStates.clear();
        synchronized (this.dirtyLock) {
            this.dirtySlots.clear();
            this.spareDirtySlots.clear();
        }
        this.refreshTitle();

        // 先在局部变量中构建资源, 避免半初始化状态对 tick 可见
        M menuHandle = this.createMenuHandle(this.manager.menuFactory(), generation);
        DisplayedSlotPath[] paths = new DisplayedSlotPath[this.layout.size()];
        ItemStack[] localSlots = new ItemStack[this.layout.size()];
        SchedulerTask tickTask = null;

        try {
            for (int windowSlot = 0; windowSlot < this.layout.size(); windowSlot++) {
                switch (this.layout.route(windowSlot)) {
                    case WindowLayout.Route.GuiRoute route -> {
                        paths[windowSlot] = new DisplayedSlotPath(this, windowSlot, route.gui(), route.guiSlot());
                    }
                    case WindowLayout.Route.PlayerRoute route -> {
                        localSlots[windowSlot] = ItemUtils.copyOrEmpty(this.viewer.getInventory().getItem(route.inventorySlot()));
                    }
                }
            }

            // 构造路径会标记初始 dirty; 全部路径就绪后统一渲染一次, 后续到达的通知留给首个 tick
            this.renderDirtySlots(this.takeDirtySlots(), paths, localSlots);
            this.prepareVirtualContent(menuHandle, localSlots);

            // 先安排周期 tick, 再发送初始完整状态, 两者都成功才发布打开状态
            tickTask = this.manager.startTick(this);
            if (tickTask == null) {
                throw new ViewerUnavailableException();
            }
            menuHandle.prepareOpen(replacingWindow);
            MenuHandle.CursorSnapshot localCursor = this.renderCursor(menuHandle.cursor());
            menuHandle.open(this.title, this.protocolSlots(localSlots), localCursor);

            this.menuHandle = menuHandle;
            this.paths = paths;
            this.localSlots = localSlots;
            this.localCursor = localCursor;
            this.tickTask = tickTask;
            this.playerInventoryVersion = menuHandle.playerInventoryVersion();
            this.open = true;
            this.cursorDirty = false;
            this.forceFull = false;
            this.menuDirty = false;
        } catch (RuntimeException | Error throwable) {
            // 仅清理局部资源, 因为对象字段尚未发布这次打开状态
            if (tickTask != null) {
                tickTask.cancel();
            }
            try {
                menuHandle.close(InventoryCloseEvent.Reason.PLUGIN);
            } catch (RuntimeException | Error closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            closePaths(paths, throwable);
            throw throwable;
        }
    }

    /**
     * 为一次打开创建与具体 Window 类型匹配的协议菜单.
     *
     * @param factory 菜单工厂
     * @param generation 本次打开代际
     * @return 尚未打开的菜单句柄
     */
    @NotNull
    protected abstract M createMenuHandle(@NotNull MenuFactory factory, long generation);

    /**
     * 把逻辑尾部区域的渲染结果投影到具体菜单状态.
     * <p>调用发生在玩家实体线程, 初次打开和后续虚拟槽位或全量状态刷新都会执行.
     * 默认实现不做任何处理.
     *
     * @param menuHandle 当前菜单句柄
     * @param logicalSlots 包含虚拟尾部的完整逻辑槽位快照
     */
    protected void prepareVirtualContent(@NotNull M menuHandle, ItemStack @NotNull [] logicalSlots) {
    }

    /**
     * 直接把一个逻辑 GUI 槽位交给其当前显示 Item 处理.
     * <p>此路径不桥接 Bukkit InventoryClickEvent, 冻结、背景和空路径仍由
     * {@link DisplayedSlotPath} 的普通 Item 规则决定是否分发.
     *
     * @param windowSlot 逻辑 Window 槽位
     * @param clickType 点击类型
     */
    protected final void dispatchItemClick(int windowSlot, @NotNull ClickType clickType) {
        this.requirePath(windowSlot).handleClick(new ItemClick(clickType, this.viewer, this, windowSlot, -1));
    }


    /**
     * 在玩家的实体线程关闭已打开的 Window.
     * 先撤销本地可见状态并停掉输入, 再关闭菜单、释放路径、处理 fallback 与关闭回调.
     */
    void closeOnEntity(InventoryCloseEvent.Reason reason) {
        Throwable failure = this.teardownOnEntity(reason);
        // 只有玩家主动关闭才进入 fallback, 然后通知关闭处理器
        this.openFallback(reason);
        this.fireCloseHandlers(reason);
        ThrowableUtils.throwIfUnchecked(failure);
    }

    /**
     * 在调度器退役或插件关闭时回收本地资源.
     * 此路径不发送客户端关闭包, 也不触发 fallback 或用户关闭处理器.
     *
     * @return 退役前是否处于打开状态
     */
    boolean retire() {
        boolean wasOpen = this.open;
        Throwable failure = this.teardownOnEntity(null);
        if (failure != null) {
            this.manager.report("Failed to retire Window session", failure);
        }
        return wasOpen;
    }

    /**
     * 执行一次玩家实体 tick.
     * 先有界处理协议输入, 再推进周期刷新、玩家物品栏镜像与批量协议同步.
     */
    void tick(ScheduledTask task) {
        M menuHandle = this.menuHandle;
        if (!this.open || menuHandle == null) {
            return;
        }

        // 我们的PacketHandler在Paper Limiter之前, 所以还是限制一下包速率, 防止恶意攻击.
        //异常后按 UNKNOWN 原因强制关闭 Window, 关闭失败只上报不再抛出.
        if (menuHandle.hasInputOverflowed()) {
            try {
                this.manager.report(
                        "Closing Window because its incoming packet queue overflowed",
                        new IllegalStateException("incoming packet queue capacity exceeded")
                );
                this.manager.closeNow(this, InventoryCloseEvent.Reason.UNKNOWN);
            } catch (RuntimeException | Error throwable) {
                this.report("Failed to close Window after a protocol failure", throwable);
                return;
            }
        }

        // 限制每 tick 的输入量, 防止单个玩家耗尽实体线程预算
        List<MenuInput> inputs = menuHandle.drainInputs(INCOMING_PER_TICK);
        for (int index = 0; index < inputs.size(); index++) {
            try {
                this.handleInput(inputs.get(index));
            } catch (Throwable throwable) {
                this.clickInterpreter.reset();
                this.cursorDirty = true;
                this.forceFull = true;
                this.manager.report("Failed to process a Window interaction", throwable);
            }
            if (!this.open) {
                return;
            }
        }

        // 输入稳定后再汇总所有本 tick 的失效并发送一次同步
        this.windowTick++;
        this.refreshLinkedInventories();
        this.invalidatePeriodicSlots();
        this.refreshPlayerState();
        this.flush(false);
    }

    /**
     * 更新本地标题快照, 并在打开状态下标记下一次 tick 重开容器标题.
     * 多次调用只保留最后一个标题, 避免重复发送 OpenScreen 与全量内容包.
     */
    void notifyUpdateTitle(Component title) {
        this.title = title;
        if (this.open) {
            this.titleDirty = true;
        }
    }

    /**
     * 重新求值标题 supplier 并发布到本地快照, 打开状态下安排重开标题.
     */
    void notifyUpdateTitle() {
        this.notifyUpdateTitle(this.refreshTitle());
    }

    /**
     * 在打开状态已经提交后按列表快照依次运行打开处理器, 单个处理器失败不会阻止后续处理器.
     */
    void fireOpenHandlers() {
        this.openHandlers.forEachIsolated(Runnable::run, "Failed to handle Window open", this.manager::report);
    }

    /**
     * 判断给定 Bukkit 视图是否由当前菜单提供.
     *
     * @param view Bukkit 视图
     * @return 属于当前菜单时为 true
     */
    boolean owns(InventoryView view) {
        return this.menuHandle != null && this.menuHandle.view() == view;
    }

    /**
     * 返回当前菜单的 Bukkit 视图, 菜单未打开时抛出 IllegalStateException.
     *
     * @return 菜单视图
     */
    InventoryView inventoryView() {
        if (this.menuHandle == null) {
            throw new IllegalStateException("Window menu is not open");
        }
        return this.menuHandle.view();
    }

    /**
     * 将无需向调用者返回结果的具体 Window 命令串行化到玩家的实体线程.
     * 命令执行或调度失败时统一上报, 玩家实体退役后静默完成.
     *
     * @param action 命令
     * @param failureMessage 失败报告文本
     */
    protected final void submit(@NotNull Runnable action, @NotNull String failureMessage) {
        this.submit(
                () -> {
                    action.run();
                    return null;
                },
                () -> null
        ).exceptionally(throwable -> {
            this.report(failureMessage, throwable);
            return null;
        });
    }

    /**
     * 将需要返回结果的具体 Window 命令串行化到玩家的实体线程.
     *
     * @param action 玩家仍可调度时执行的操作
     * @param retiredAction 玩家实体退役后执行的替代操作
     * @param <T> 命令结果类型
     * @return 命令完成阶段
     */
    @NotNull
    protected final <T> CompletionStage<T> submit(@NotNull Callable<T> action, @NotNull Callable<T> retiredAction) {
        return this.manager.submit(this, action, retiredAction);
    }

    /**
     * 标记类型化菜单存在不依赖物品槽位的待同步状态.
     */
    protected final void notifySynchronize() {
        this.menuDirty = true;
    }

    /**
     * 报告具体 Window 处理器中的异常.
     *
     * @param message 错误说明
     * @param throwable 异常
     */
    protected final void report(@NotNull String message, @NotNull Throwable throwable) {
        this.manager.report(message, throwable);
    }

    /**
     * 返回当前已打开的类型化菜单; Window 关闭时返回 null.
     *
     * @return 当前菜单
     */
    @Nullable
    protected final M menuHandle() {
        return this.menuHandle;
    }

    /**
     * 返回窗口顶部协议槽位数量.
     *
     * @return 顶部槽位数量
     */
    protected final int upperSize() {
        return this.layout.upperSize();
    }

    /**
     * 将已通过 generation 筛选的协议输入分派到对应处理流程.
     */
    private void handleInput(MenuInput input) {
        switch (input) {
            case MenuInput.Common.Interaction interaction -> this.handleInteraction(interaction);
            case MenuInput.Common.Close close -> this.handleClose(close);
            case MenuInput.Common.BundleSelection selection -> this.handleBundleSelection(selection);
            case MenuInput.Common.Pong pong -> this.handlePong(pong);
            case MenuInput.WindowSpecific windowSpecific -> this.handleWindowInput(windowSpecific);
        }
    }

    /**
     * 处理只属于具体 Window 类型的协议输入.
     *
     * <p>共享生命周期只保证输入顺序和实体线程所有权, 具体语义由对应 Window 实现解释.</p>
     *
     * @param input Window 专属输入
     */
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
    }

    /**
     * 校验容器状态、解释点击或拖拽步骤并分派给 GUI 或容器外处理器.
     * 不可信输入请求完整恢复, 合法或被 Bukkit 取消的输入只复核客户端预测涉及的槽位.
     */
    private void handleInteraction(MenuInput.Common.Interaction interaction) {
        M menu = this.menuHandle;
        if (menu == null || !menu.accepts(interaction)) {
            this.clickInterpreter.reset();
            this.forceFull = true;
            return;
        }
        this.cursorDirty = true;
        ClickInterpreter.Result result = this.clickInterpreter.interpret(interaction, this.layout, this.generation);
        switch (result) {
            case ClickInterpreter.Result.Pending ignoredPending -> {}
            case ClickInterpreter.Result.Rejected ignoredRejection -> this.forceFull = true;
            case ClickInterpreter.Result.SingleClick click -> this.handleSingleClick(click, menu);
            case ClickInterpreter.Result.Drag drag -> this.handleDrag(drag, menu);
        }
    }

    /**
     * 对已解释的单次点击执行 Bukkit 桥接、重入复验和语义分派.
     * <p>库存与玩家区域槽位交给点击语义引擎(库存侧为事务, 玩家侧直接执行);
     * Item 与空槽位保持原有 Item 分派. 语义执行后涉及槽位已被标脏,
     * 客户端预测由同一 tick 的 flush 以权威状态收敛.
     */
    private void handleSingleClick(ClickInterpreter.Result.SingleClick click, MenuHandle menu) {
        long interactionGeneration = this.generation;
        int interactionStateId = menu.stateId();
        if (!this.manager.bukkitBridge().allowClick(this, click)) {
            return;
        }
        if (!this.isInteractionCurrent(interactionGeneration, menu, interactionStateId)) {
            return;
        }
        switch (click.target()) {
            case ClickInterpreter.Target.GuiTarget(var windowSlot) -> {
                if (!ClickSemantics.handleClick(this.semanticsContext, click.clickType(), click.hotbarButton(), windowSlot)) {
                    this.requirePath(windowSlot).handleClick(new ItemClick(click.clickType(), this.viewer, this, windowSlot, click.hotbarButton()));
                }
            }
            case ClickInterpreter.Target.PlayerTarget(var windowSlot, var ignoredInventorySlot) ->
                    ClickSemantics.handleClick(this.semanticsContext, click.clickType(), click.hotbarButton(), windowSlot);
            case ClickInterpreter.Target.OutsideTarget ignoredOutside -> {
                ClickSemantics.handleOutsideClick(this.semanticsContext, click.clickType());
                ClickEvent event = new ClickEvent(this.viewer, click.clickType(), click.hotbarButton());
                this.outsideClickHandlers.forEachIsolated(
                        handler -> handler.accept(event),
                        "Failed to handle Window outside click",
                        this.manager::report
                );
            }
        }
    }

    /**
     * 对已完成的 QUICK_CRAFT 执行 Bukkit 桥接、重入复验和拖拽分配.
     * <p>参与的库存槽位构成一个事务, 玩家背包槽位在提交成功后应用;
     * Item 槽位不参与分配.
     */
    private void handleDrag(ClickInterpreter.Result.Drag drag, MenuHandle menu) {
        long interactionGeneration = this.generation;
        int interactionStateId = menu.stateId();
        if (!this.manager.bukkitBridge().allowDrag(this, drag.clickType(), drag.slots())) {
            return;
        }
        if (!this.isInteractionCurrent(interactionGeneration, menu, interactionStateId)) {
            return;
        }
        ClickSemantics.handleDrag(this.semanticsContext, drag.clickType(), drag.slots());
    }

    /**
     * 处理客户端关闭包.
     * 不可关闭的 Window 立即以当前标题和全量内容重新打开, 而非让 Bukkit 外部关闭处理器 veto.
     */
    private void handleClose(MenuInput.Common.Close packet) {
        if (this.menuHandle == null || packet.containerId() != this.menuHandle.containerId()) {
            return;
        }
        this.clickInterpreter.reset();
        if (this.closeable) {
            this.manager.closeNow(this, InventoryCloseEvent.Reason.PLAYER);
        } else {
            this.forceFull = true;
            this.flush(true);
        }
    }

    /**
     * 处理客户端收纳袋选择包.
     * 选择只转发给 GUI 槽位, 处理后强制完整同步以纠正客户端的本地预测.
     */
    private void handleBundleSelection(MenuInput.Common.BundleSelection packet) {
        this.clickInterpreter.reset();
        if (this.menuHandle == null || packet.containerId() != this.menuHandle.containerId()) {
            return;
        }
        if (packet.slot() < 0 || packet.slot() >= this.layout.protocolSize() || packet.selectedIndex() < -1) {
            this.forceFull = true;
            return;
        }
        if (this.layout.route(packet.slot()) instanceof WindowLayout.Route.GuiRoute) {
            this.requirePath(packet.slot()).handleBundleSelect(
                    new BundleSelect(this.viewer, packet.selectedIndex())
            );
        }
        this.forceFull = true;
    }

    /**
     * 将客户端 Pong 与待确认的服务器窗口状态关联, 并通知状态确认处理器.
     */
    private void handlePong(MenuInput.Common.Pong packet) {
        PendingWindowState pending = this.pendingWindowStates.remove(packet.id());
        if (pending == null) {
            return;
        }
        this.clientWindowState = pending.state();
        this.windowStateChangeHandlers.forEachIsolated(
                handler -> handler.accept(this.clientWindowState),
                "Failed to handle Window state acknowledgement",
                this.manager::report
        );
    }

    /**
     * 返回指定窗口槽位的显示路径, 槽位没有 GUI 路径时抛出 IllegalStateException.
     *
     * @param windowSlot 窗口槽位
     * @return 显示路径
     */
    private DisplayedSlotPath requirePath(int windowSlot) {
        DisplayedSlotPath[] paths = this.paths;
        if (paths == null || paths[windowSlot] == null) {
            throw new IllegalStateException("window slot has no displayed GUI path: " + windowSlot);
        }
        return paths[windowSlot];
    }

    /**
     * 确认桥接返回后本次交互仍然有效.
     * Bukkit 事件处理器可能已关闭或重开 Window, generation、菜单实例和 state id 任一变化都丢弃该交互.
     *
     * @param interactionGeneration 交互开始时的 generation
     * @param interactionMenu 交互开始时的菜单
     * @param interactionStateId 交互开始时的 state id
     * @return 交互仍然有效时为 true
     */
    private boolean isInteractionCurrent(long interactionGeneration, MenuHandle interactionMenu, int interactionStateId) {
        return this.open
                && this.generation == interactionGeneration
                && this.menuHandle == interactionMenu
                && interactionMenu.stateId() == interactionStateId;
    }

    /**
     * 点击语义引擎的交互上下文: 槽位路由查询与光标, 玩家背包的权威读写.
     * 全部方法只在玩家实体线程被点击处理路径调用.
     */
    private final class SemanticsContext implements ClickSemantics.Context {

        @Override
        @NotNull
        public Player viewer() {
            return AbstractWindow.this.viewer;
        }

        @Override
        @Nullable
        public ClickSemantics.LinkedSlot linkAt(int windowSlot) {
            if (!(AbstractWindow.this.layout.route(windowSlot) instanceof WindowLayout.Route.GuiRoute)) {
                return null;
            }
            SlotElement.InventoryLink link = AbstractWindow.this.requirePath(windowSlot).inventoryLink();
            return link == null ? null : new ClickSemantics.LinkedSlot(link.inventory(), link.slot());
        }

        @Override
        public boolean frozenAt(int windowSlot) {
            return AbstractWindow.this.requirePath(windowSlot).frozen();
        }

        @Override
        public int lowerSlotAt(int windowSlot) {
            return AbstractWindow.this.layout.route(windowSlot) instanceof WindowLayout.Route.PlayerRoute(var inventorySlot)
                    ? inventorySlot
                    : -1;
        }

        @Override
        public boolean hasPlayerInventory() {
            return AbstractWindow.this.layout.hasPlayerLower();
        }

        @Override
        @NotNull
        public List<Inventory> linkedInventories() {
            return AbstractWindow.this.collectLinkedInventories();
        }

        @Override
        @NotNull
        public ItemStack cursor() {
            M menu = AbstractWindow.this.menuHandle;
            return menu != null ? menu.cursor() : ItemStack.empty();
        }

        @Override
        public void cursor(@NotNull ItemStack cursor) {
            M menu = AbstractWindow.this.menuHandle;
            if (menu != null) {
                menu.cursor(cursor);
            }
            AbstractWindow.this.cursorDirty = true;
        }

        @Override
        @Nullable
        public ItemStack lowerAt(int inventorySlot) {
            // 背包 getItem 返回 live 镜像, 防御拷贝后再交给语义计算
            return ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(AbstractWindow.this.viewer.getInventory().getItem(inventorySlot)));
        }

        @Override
        public void lowerAt(int inventorySlot, @Nullable ItemStack item) {
            AbstractWindow.this.viewer.getInventory().setItem(inventorySlot, item);

            // 同步窗口本地快照并标脏, 客户端在本 tick flush 中收到权威确认
            int windowSlot = AbstractWindow.this.layout.windowSlotOfInventorySlot(inventorySlot);
            ItemStack[] localSlots = AbstractWindow.this.localSlots;
            if (localSlots != null) {
                localSlots[windowSlot] = ItemUtils.copyOrEmpty(item);
            }
            AbstractWindow.this.notifyUpdate(windowSlot);
        }

        @Override
        @Nullable
        public ItemStack offhand() {
            return ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(AbstractWindow.this.viewer.getInventory().getItemInOffHand()));
        }

        @Override
        public void offhand(@Nullable ItemStack item) {
            AbstractWindow.this.viewer.getInventory().setItemInOffHand(item);
        }

        @Override
        public void drop(@NotNull ItemStack item) {
            // 走玩家丢弃路径: 触发 PlayerDropItemEvent, 携带投掷归属与原版轨迹
            AbstractWindow.this.viewer.dropItem(item);
        }

        @Override
        public void markDirty(int windowSlot) {
            AbstractWindow.this.notifyUpdate(windowSlot);
        }
    }

    // 按显示顺序收集去重的连接库存作为点击语义的目标域: 排除冻结槽与协议外
    // (虚拟尾部)槽连接的库存, 冻结的展示库存不得被转移或收集穿透
    private List<Inventory> collectLinkedInventories() {
        LinkedHashSet<Inventory> inventories = new LinkedHashSet<>();
        this.forEachLinkedInventory(true, inventories::add);
        return List.copyOf(inventories);
    }

    /**
     * 驱动全部连接库存与外部真相对账: 镜像型根库存把被引用容器的外部变更
     * 吸收为 External 事件, 快照型无操作. 每 tick 一次, 先于渲染消费;
     * 对账不是语义操作, 冻结与虚拟槽的连接同样保持同步.
     */
    private void refreshLinkedInventories() {
        LinkedHashSet<Inventory> seen = new LinkedHashSet<>();
        this.forEachLinkedInventory(false, inventory -> {
            if (seen.add(inventory)) {
                inventory.refresh();
            }
        });
    }

    // 遍历显示路径终点的库存连接; semanticOnly 时跳过冻结槽与协议外槽
    private void forEachLinkedInventory(boolean semanticOnly, Consumer<Inventory> action) {
        DisplayedSlotPath[] paths = this.paths;
        if (paths == null) {
            return;
        }
        for (int windowSlot = 0; windowSlot < paths.length; windowSlot++) {
            DisplayedSlotPath path = paths[windowSlot];
            if (path == null) {
                continue;
            }
            if (semanticOnly && (windowSlot >= this.layout.protocolSize() || path.frozen())) {
                continue;
            }
            SlotElement.InventoryLink link = path.inventoryLink();
            if (link != null) {
                action.accept(link.inventory());
            }
        }
    }

    /**
     * 标记到达刷新周期的显示路径, 由同一 tick 的 flush 统一渲染.
     */
    private void invalidatePeriodicSlots() {
        DisplayedSlotPath[] paths = this.paths;
        if (paths == null) {
            return;
        }
        for (int windowSlot = 0; windowSlot < paths.length; windowSlot++) {
            DisplayedSlotPath path = paths[windowSlot];
            if (path != null && path.refreshPlan().isDue(this.windowTick)) {
                this.notifyUpdate(windowSlot);
            }
        }
    }

    /**
     * 用 NMS 版本门控玩家物品栏扫描, 并低频审计可能绕过版本信号的槽位与光标变化.
     */
    private void refreshPlayerState() {
        MenuHandle menu = this.menuHandle;
        ItemStack[] localSlots = this.localSlots;
        if (menu == null || localSlots == null) {
            return;
        }

        int currentVersion = menu.playerInventoryVersion();
        boolean auditDue = this.windowTick % PLAYER_INVENTORY_AUDIT_INTERVAL == 0;
        if (currentVersion == this.playerInventoryVersion && !auditDue) {
            return;
        }
        this.playerInventoryVersion = currentVersion;
        if (auditDue) {
            this.cursorDirty = true;
        }

        for (int windowSlot = 0; windowSlot < this.layout.size(); windowSlot++) {
            if (this.layout.route(windowSlot) instanceof WindowLayout.Route.PlayerRoute(var inventorySlot)) {
                ItemStack playerItem = this.viewer.getInventory().getItem(inventorySlot);
                if (!sameItem(localSlots[windowSlot], playerItem)) {
                    localSlots[windowSlot] = ItemUtils.copyOrEmpty(playerItem);
                    this.notifyUpdate(windowSlot);
                }
            }
        }
    }

    /**
     * 非对称比较本地快照与玩家物品栏现状.
     * 右侧为 null 表示空槽位, 只有两侧都非空时才比较数量与相似度.
     *
     * @param left 本地快照
     * @param right 当前物品, 可能为 null
     * @return 两者表示同一物品时为 true
     */
    private static boolean sameItem(@NotNull ItemStack left, @Nullable ItemStack right) {
        if (right == null || right.isEmpty()) {
            return left.isEmpty();
        }
        return !left.isEmpty() && left.getAmount() == right.getAmount() && left.isSimilar(right);
    }

    /**
     * 汇总 dirty 槽位、光标和标题变化并交给菜单 Adapter 收敛远端状态.
     * 标题变化必须走重开窗口和完整内容同步, 发送失败时保留状态以便下一 tick 重试.
     */
    private void flush(boolean reopenTitle) {
        M menu = this.menuHandle;
        ItemStack[] localSlots = this.localSlots;
        DisplayedSlotPath[] paths = this.paths;
        if (!this.open || menu == null || localSlots == null || paths == null) {
            return;
        }

        // 先消费跨线程写入的失效集合, 再在实体线程渲染最终槽位快照
        BitSet dirty = this.takeDirtySlots();
        this.renderDirtySlots(dirty, paths, localSlots);

        boolean virtualDirty = dirty.nextSetBit(this.layout.protocolSize()) >= 0;
        boolean reopen = reopenTitle || this.titleDirty;
        boolean full = this.forceFull || reopen;
        if (dirty.isEmpty() && !this.cursorDirty && !full && !this.menuDirty) {
            return;
        }

        try {
            if (virtualDirty || full) {
                this.prepareVirtualContent(menu, localSlots);
            }
            if (virtualDirty) {
                dirty.clear(this.layout.protocolSize(), dirty.length());
            }
            ItemStack[] protocolSlots = this.protocolSlots(localSlots);
            MenuHandle.CursorSnapshot cursor = this.localCursor;
            if (cursor == null || this.cursorDirty) {
                cursor = this.renderCursor(menu.cursor());
            }
            if (reopen) {
                menu.updateTitle(this.title, protocolSlots, cursor);
            } else {
                menu.synchronize(protocolSlots, dirty, cursor, this.cursorDirty, full);
            }
            this.localCursor = cursor;
            this.cursorDirty = false;
            this.forceFull = false;
            this.menuDirty = false;
            this.titleDirty = false;
        } catch (RuntimeException | Error throwable) {
            this.cursorDirty = true;
            this.forceFull = true;
            this.menuDirty = true;
            this.titleDirty = reopen;
            this.manager.report("Failed to synchronize Window", throwable);
        }
    }

    /**
     * 取出本批 dirty 槽位并立即清空活动缓冲, 使通知线程可以继续写入下一批.
     * 返回的是复用缓冲, 调用方只能读取且不能跨 tick 持有.
     *
     * @return 本批 dirty 槽位
     */
    private BitSet takeDirtySlots() {
        synchronized (this.dirtyLock) {
            if (this.dirtySlots.isEmpty()) {
                return EMPTY_DIRTY_SLOTS;
            }

            // 交换活动与备用缓冲, 使通知线程可以立即继续写入下一批 dirty 槽位
            BitSet dirty = this.dirtySlots;
            this.dirtySlots = this.spareDirtySlots;
            this.dirtySlots.clear();
            this.spareDirtySlots = dirty;
            return dirty;
        }
    }

    /**
     * 把 dirty 槽位集合中的 GUI 槽位渲染进本地快照.
     *
     * @param dirty 本批 dirty 槽位
     * @param paths 显示路径
     * @param localSlots 本地槽位快照
     */
    private void renderDirtySlots(BitSet dirty, DisplayedSlotPath[] paths, ItemStack[] localSlots) {
        for (
                int windowSlot = dirty.nextSetBit(0);
                windowSlot >= 0;
                windowSlot = dirty.nextSetBit(windowSlot + 1)
        ) {
            DisplayedSlotPath path = paths[windowSlot];
            if (path != null) {
                localSlots[windowSlot] = this.render(path, windowSlot, localSlots[windowSlot]);
            }
        }
    }

    /**
     * 返回菜单协议可见的物理槽位前缀, 普通布局直接复用原数组.
     */
    @NotNull
    private ItemStack[] protocolSlots(ItemStack @NotNull [] logicalSlots) {
        if (logicalSlots.length == this.layout.protocolSize()) {
            return logicalSlots;
        }
        return Arrays.copyOf(logicalSlots, this.layout.protocolSize());
    }

    /**
     * 渲染单个显示路径, 失败时上报并回退到上一份本地快照.
     *
     * @param path 显示路径
     * @param windowSlot 窗口槽位
     * @param fallback 渲染失败时的回退物品
     * @return 渲染结果
     */
    private ItemStack render(DisplayedSlotPath path, int windowSlot, @Nullable ItemStack fallback) {
        try {
            return path.render();
        } catch (Throwable throwable) {
            this.manager.report("Failed to render Window slot " + windowSlot, throwable);
            return fallback == null ? ItemStack.empty() : fallback;
        }
    }

    /**
     * 渲染仅用于协议显示的光标快照.
     * 可视化器失败时回退到真实光标, 因而显示扩展不会破坏容器同步.
     */
    private MenuHandle.CursorSnapshot renderCursor(ItemStack actualCursor) {
        ItemStack actual = ItemUtils.copyOrEmpty(actualCursor);
        try {
            ItemProvider visualizer = this.cursorVisualizer.apply(actual.isEmpty() ? null : actual.clone());
            if (visualizer == null) {
                return new MenuHandle.CursorSnapshot(actual, actual.clone());
            }
            ItemStack visual = ItemUtils.copyOrEmpty(visualizer.provide(this.cursorRenderContext));
            return new MenuHandle.CursorSnapshot(actual, visual);
        } catch (Throwable throwable) {
            this.manager.report("Failed to render Window cursor visualizer", throwable);
            return new MenuHandle.CursorSnapshot(actual, actual.clone());
        }
    }

    /**
     * 撤销本次打开的本地可见状态并释放菜单、tick 与显示路径.
     * reason 为 null 表示调度退役, 不发送客户端关闭包; 否则按该原因走正常菜单关闭.
     *
     * @param reason 关闭原因, 退役路径为 null
     * @return 清理过程中的第一个失败, 没有失败时为 null
     */
    @Nullable
    private Throwable teardownOnEntity(@Nullable InventoryCloseEvent.Reason reason) {
        // 使后续输入与 tick 立即失效
        this.open = false;
        this.generation++;
        this.clickInterpreter.reset();

        SchedulerTask previousTickTask = this.tickTask;
        M previousMenu = this.menuHandle;
        DisplayedSlotPath[] previousPaths = this.paths;
        this.tickTask = null;
        this.menuHandle = null;
        this.paths = null;
        this.localSlots = null;
        this.localCursor = null;
        this.menuDirty = false;
        this.titleDirty = false;
        this.pendingWindowStates.clear();

        // 资源关闭应尽量完整执行, 最后才把第一个失败交给调用方
        Throwable failure = null;
        if (previousTickTask != null) {
            previousTickTask.cancel();
        }
        if (previousMenu != null) {
            try {
                if (reason == null) {
                    previousMenu.retire();
                } else {
                    previousMenu.close(reason);
                }
            } catch (RuntimeException | Error throwable) {
                failure = throwable;
            }
        }
        return closePaths(previousPaths, failure);
    }

    /**
     * 在玩家主动关闭后解析并请求打开 fallback Window.
     * Supplier 和打开失败都会被隔离并报告, 不影响本 Window 的清理.
     */
    private void openFallback(InventoryCloseEvent.Reason reason) {
        if (reason != InventoryCloseEvent.Reason.PLAYER) {
            return;
        }
        Window fallback;
        try {
            fallback = this.fallbackWindow.get();
        } catch (Throwable throwable) {
            this.manager.report("Failed to resolve Window fallback", throwable);
            return;
        }
        if (fallback == null) {
            return;
        }
        fallback.open().exceptionally(throwable -> {
            this.manager.report("Failed to open Window fallback", throwable);
            return null;
        });
    }

    /**
     * 按列表快照顺序运行关闭处理器, 单个处理器失败不影响后续处理器.
     */
    private void fireCloseHandlers(InventoryCloseEvent.Reason reason) {
        this.closeHandlers.forEachIsolated(
                handler -> handler.accept(reason),
                "Failed to handle Window close",
                this.manager::report
        );
    }

    /**
     * 按相反槽位顺序关闭显示路径, 并把后续关闭异常附加到第一个异常.
     */
    private static Throwable closePaths(@Nullable DisplayedSlotPath[] paths, @Nullable Throwable failure) {
        if (paths == null) {
            return failure;
        }
        for (int index = paths.length - 1; index >= 0; index--) {
            DisplayedSlotPath path = paths[index];
            if (path == null) {
                continue;
            }
            try {
                path.close();
            } catch (RuntimeException | Error throwable) {
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
            }
        }
        return failure;
    }

    /**
     * 求值标题 supplier 并写入本地快照, supplier 返回 null 时立即失败.
     *
     * @return 新标题
     */
    private Component refreshTitle() {
        Component component = this.titleSupplier.get();
        this.title = component != null ? component : Component.empty();
        return this.title;
    }

    /**
     * 更新服务器窗口状态并发送独立 Ping.
     * Ping id 随机生成并暂存状态, 收到同 id Pong 后才推进客户端状态快照.
     */
    private void updateWindowStateOnEntity(int windowState) {
        this.serverWindowState = windowState;
        MenuHandle menu = this.menuHandle;
        if (!this.open || menu == null) {
            return;
        }
        long now = System.currentTimeMillis();
        this.pendingWindowStates.entrySet().removeIf(
                entry -> now - entry.getValue().createdAtMillis() > PING_TIMEOUT_MILLIS
        );
        int pingId;
        do {
            pingId = ThreadLocalRandom.current().nextInt();
        } while (this.pendingWindowStates.containsKey(pingId));
        this.pendingWindowStates.put(pingId, new PendingWindowState(windowState, now));
        menu.sendPing(pingId);
    }

    private record PendingWindowState(int state, long createdAtMillis) {
    }
}
