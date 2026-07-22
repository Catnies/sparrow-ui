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
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.util.ItemSnapshots;
import net.momirealms.sparrow.ui.util.MiscUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 各类 Window 共用的生命周期、槽位路由和协议同步引擎.
 * <p>公开状态通过 volatile 快照提供跨线程读取, 菜单、路径和容器状态只在查看者实体线程访问.
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
    private static final BitSet EMPTY_DIRTY_SLOTS = new BitSet();

    private final WindowManager manager;
    private final Player viewer;
    private final WindowLayout layout;
    private final Object dirtyLock = new Object();
    private final ClickInterpreter clickInterpreter = new ClickInterpreter();
    private final RenderContext cursorRenderContext;
    private final Map<Integer, PendingWindowState> pendingWindowStates = new HashMap<>();

    private volatile Component title;
    private volatile Supplier<? extends Component> titleSupplier;
    private volatile boolean closeable;
    private volatile boolean open;
    private volatile long generation;
    private volatile List<Runnable> openHandlers;
    private volatile List<Consumer<InventoryCloseEvent.Reason>> closeHandlers;
    private volatile List<Consumer<ClickEvent>> outsideClickHandlers;
    private volatile Supplier<? extends @Nullable Window> fallbackWindow;
    private volatile int serverWindowState;
    private volatile int clientWindowState;
    private volatile List<Consumer<Integer>> windowStateChangeHandlers;
    private volatile Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer;

    private @Nullable M menuHandle;
    private @Nullable DisplayedSlotPath[] paths;
    private @Nullable ItemStack[] localSlots;
    private @Nullable SchedulerTask tickTask;
    private @Nullable Component pendingReopenTitle;
    private BitSet dirtySlots;
    private BitSet spareDirtySlots;
    private long windowTick;
    private int playerInventoryVersion;
    private boolean cursorDirty;
    private boolean forceFull;
    private boolean menuDirty;

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
        this.openHandlers = settings.openHandlers();
        this.closeHandlers = settings.closeHandlers();
        this.outsideClickHandlers = settings.outsideClickHandlers();
        this.fallbackWindow = settings.fallbackWindow();
        this.serverWindowState = settings.windowState();
        this.windowStateChangeHandlers = settings.windowStateChangeHandlers();
        this.cursorVisualizer = settings.cursorVisualizer();
        this.dirtySlots = new BitSet(layout.size());
        this.spareDirtySlots = new BitSet(layout.size());
        this.cursorRenderContext = RenderContext.cursor(this);
    }

    /**
     * 为一次打开创建与具体 Window 类型匹配的协议菜单.
     *
     * @param factory 菜单工厂
     * @param generation 本次打开代际
     * @return 尚未打开的菜单句柄
     */
    protected abstract @NotNull M createMenuHandle(@NotNull MenuFactory factory, long generation);

    /**
     * 返回窗口顶部协议槽位数量.
     *
     * @return 顶部槽位数量
     */
    protected final int topSlots() {
        return this.layout.topSlots();
    }

    /**
     * 返回当前已打开的类型化菜单; Window 关闭时返回 null.
     *
     * @return 当前菜单
     */
    protected final @Nullable M menuHandle() {
        return this.menuHandle;
    }

    /**
     * 将具体 Window 的公开变更串行化到查看者实体线程.
     *
     * @param mutation 变更
     * @param failureMessage 失败报告文本
     */
    protected final void mutate(@NotNull Runnable mutation, @NotNull String failureMessage) {
        this.manager.mutate(this, mutation, failureMessage);
    }

    /**
     * 标记类型化菜单存在不依赖物品槽位的待同步状态.
     */
    protected final void requestMenuSynchronization() {
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
     * 处理只属于具体 Window 类型的协议输入.
     *
     * <p>共享生命周期只保证输入顺序和实体线程所有权, 具体语义由对应 Window 实现解释.</p>
     *
     * @param input Window 专属输入
     */
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
    }

    @Override
    public @NotNull List<Gui> guis() {
        return this.layout.guis();
    }

    @Override
    public SlotElement.@Nullable GuiLink guiAt(int windowSlot) {
        return this.layout.guiAt(windowSlot);
    }

    @Override
    public SlotElement.@Nullable GuiLink guiAtHotbar(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            throw new IndexOutOfBoundsException("hotbar slot out of bounds: " + hotbarSlot);
        }
        return this.layout.guiAt(this.layout.topSlots() + 27 + hotbarSlot);
    }

    @Override
    public @NotNull Player viewer() {
        return this.viewer;
    }

    @Override
    public @NotNull Component title() {
        return this.title;
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    public boolean isCloseable() {
        return this.closeable;
    }

    @Override
    public @NotNull CompletionStage<OpenResult> open() {
        return this.manager.open(this);
    }

    @Override
    public void setTitleSupplier(@NotNull Supplier<? extends Component> titleSupplier) {
        Objects.requireNonNull(titleSupplier, "titleSupplier");
        this.manager.mutate(this, () -> {
            this.titleSupplier = titleSupplier;
            this.refreshTitleOnEntity();
        }, "Failed to update Window title supplier");
    }

    @Override
    public void setTitle(@NotNull Component title) {
        Objects.requireNonNull(title, "title");
        this.manager.mutate(this, () -> {
            this.titleSupplier = () -> title;
            this.updateTitleOnEntity(title);
        }, "Failed to update Window title");
    }

    @Override
    public void updateTitle() {
        this.manager.mutate(this, this::refreshTitleOnEntity, "Failed to refresh Window title");
    }

    @Override
    public void setCloseable(boolean closeable) {
        this.manager.mutate(
                this,
                () -> this.closeable = closeable,
                "Failed to update Window closeable state"
        );
    }

    @Override
    public @NotNull CompletionStage<CloseResult> close() {
        return this.manager.close(this);
    }

    @Override
    public void setOpenHandlers(@NotNull List<? extends Runnable> openHandlers) {
        List<Runnable> copy = List.copyOf(openHandlers);
        this.manager.mutate(this, () -> this.openHandlers = copy, "Failed to replace Window open handlers");
    }

    @Override
    public @NotNull List<Runnable> getOpenHandlers() {
        return this.openHandlers;
    }

    @Override
    public void addOpenHandler(@NotNull Runnable openHandler) {
        this.manager.mutate(this,
                () -> this.openHandlers = MiscUtils.append(this.openHandlers, openHandler),
                "Failed to add Window open handler"
        );
    }

    @Override
    public void removeOpenHandler(@NotNull Runnable openHandler) {
        this.manager.mutate(this,
                () -> this.openHandlers = MiscUtils.remove(this.openHandlers, openHandler),
                "Failed to remove Window open handler"
        );
    }

    @Override
    public void setCloseHandlers(@NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> closeHandlers) {
        List<Consumer<InventoryCloseEvent.Reason>> copy = MiscUtils.copyConsumers(closeHandlers);
        this.manager.mutate(this,
                () -> this.closeHandlers = copy,
                "Failed to replace Window close handlers"
        );
    }

    @Override
    public @NotNull List<Consumer<InventoryCloseEvent.Reason>> getCloseHandlers() {
        return this.closeHandlers;
    }

    @Override
    public void addCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler) {
        Consumer<InventoryCloseEvent.Reason> handler = MiscUtils.narrowConsumer(closeHandler);
        this.manager.mutate(this,
                () -> this.closeHandlers = MiscUtils.append(this.closeHandlers, handler),
                "Failed to add Window close handler"
        );
    }

    @Override
    public void removeCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler) {
        this.manager.mutate(this,
                () -> this.closeHandlers = MiscUtils.removeConsumer(this.closeHandlers, closeHandler),
                "Failed to remove Window close handler"
        );
    }

    @Override
    public void setOutsideClickHandlers(@NotNull List<? extends Consumer<? super ClickEvent>> outsideClickHandlers) {
        List<Consumer<ClickEvent>> copy = MiscUtils.copyConsumers(outsideClickHandlers);
        this.manager.mutate(this,
                () -> this.outsideClickHandlers = copy,
                "Failed to replace Window outside click handlers"
        );
    }

    @Override
    public @NotNull List<Consumer<ClickEvent>> getOutsideClickHandlers() {
        return this.outsideClickHandlers;
    }

    @Override
    public void addOutsideClickHandler(@NotNull Consumer<? super ClickEvent> outsideClickHandler) {
        Consumer<ClickEvent> handler = MiscUtils.narrowConsumer(outsideClickHandler);
        this.manager.mutate(this,
                () -> this.outsideClickHandlers = MiscUtils.append(this.outsideClickHandlers, handler),
                "Failed to add Window outside click handler"
        );
    }

    @Override
    public void removeOutsideClickHandler(@NotNull Consumer<? super ClickEvent> outsideClickHandler) {
        this.manager.mutate(this,
                () -> this.outsideClickHandlers = MiscUtils.removeConsumer(this.outsideClickHandlers, outsideClickHandler),
                "Failed to remove Window outside click handler"
        );
    }

    @Override
    public void setFallbackWindow(@NotNull Supplier<? extends @Nullable Window> fallbackWindow) {
        Objects.requireNonNull(fallbackWindow, "fallbackWindow");
        this.manager.mutate(this,
                () -> this.fallbackWindow = fallbackWindow,
                "Failed to update Window fallback"
        );
    }

    @Override
    public void setWindowState(int windowState) {
        this.manager.mutate(this,
                () -> this.updateWindowStateOnEntity(windowState),
                "Failed to update Window state"
        );
    }

    @Override
    public void incrementWindowState() {
        this.manager.mutate(this,
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
        this.manager.mutate(this,
                () -> this.windowStateChangeHandlers = copy,
                "Failed to replace Window state handlers"
        );
    }

    @Override
    public @NotNull List<Consumer<Integer>> getWindowStateChangeHandlers() {
        return this.windowStateChangeHandlers;
    }

    @Override
    public void addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        Consumer<Integer> copied = MiscUtils.narrowConsumer(handler);
        this.manager.mutate(this,
                () -> this.windowStateChangeHandlers = MiscUtils.append(this.windowStateChangeHandlers, copied),
                "Failed to add Window state handler"
        );
    }

    @Override
    public void removeWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        this.manager.mutate(this,
                () -> this.windowStateChangeHandlers = MiscUtils.removeConsumer(this.windowStateChangeHandlers, handler),
                "Failed to remove Window state handler"
        );
    }

    @Override
    public void setCursorVisualizer(@NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer) {
        Objects.requireNonNull(cursorVisualizer, "cursorVisualizer");
        this.manager.mutate(this,
                () -> {
                    this.cursorVisualizer = cursorVisualizer;
                    this.cursorDirty = true;
                },
                "Failed to update Window cursor visualizer"
        );
    }

    @Override
    public @NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> getCursorVisualizer() {
        return this.cursorVisualizer;
    }

    @Override
    public void sendAllDataToViewer() {
        this.manager.mutate(this,
                () -> {
                    this.forceFull = true;
                    this.flush(false, null);
                },
                "Failed to resend Window data"
        );
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

    /**
     * 在查看者实体线程创建菜单、显示路径和初始协议状态.
     * 所有资源在初始完整包成功排入 Netty event loop 后才发布到字段, 失败时按相反方向回滚.
     */
    void openOnEntity(long generation) {
        if (this.open) {
            return;
        }

        // 初始化本次打开的 generation 相关状态
        this.generation = generation;
        this.windowTick = 0;
        this.cursorDirty = true;
        this.forceFull = true;
        this.menuDirty = true;
        this.pendingReopenTitle = null;
        this.clickInterpreter.reset();
        this.pendingWindowStates.clear();
        synchronized (this.dirtyLock) {
            this.dirtySlots.clear();
            this.spareDirtySlots.clear();
        }
        this.refreshTitleValueOnEntity();

        // 先在局部变量中构建资源, 避免半初始化状态对 tick 可见
        M menuHandle = this.createMenuHandle(this.manager.menuFactory(), generation);
        DisplayedSlotPath[] paths = new DisplayedSlotPath[this.layout.size()];
        ItemStack[] localSlots = new ItemStack[this.layout.size()];
        SchedulerTask tickTask = null;

        try {
            for (int windowSlot = 0; windowSlot < this.layout.size(); windowSlot++) {
                switch (this.layout.route(windowSlot)) {
                    case WindowLayout.GuiRoute route -> {
                        DisplayedSlotPath path = new DisplayedSlotPath(
                                this,
                                windowSlot,
                                route.gui(),
                                route.guiSlot()
                        );
                        paths[windowSlot] = path;
                        localSlots[windowSlot] = this.render(path, windowSlot, null);
                    }
                    case WindowLayout.PlayerRoute route -> localSlots[windowSlot] = ItemSnapshots.copyOrEmpty(
                            this.viewer.getInventory().getItem(route.inventorySlot())
                    );
                }
            }

            // 构造路径会标记初始 dirty; 在 Full 前统一重渲染, 后续到达的通知留给首个 tick
            this.renderDirtySlots(this.takeDirtySlots(), paths, localSlots);

            // 先安排周期 tick, 再发送初始完整状态, 两者都成功才发布打开状态
            tickTask = this.manager.startTick(this);
            if (tickTask == null) {
                throw new ViewerUnavailableException();
            }
            menuHandle.open(this.title, localSlots, this.renderCursor());

            this.menuHandle = menuHandle;
            this.paths = paths;
            this.localSlots = localSlots;
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
                menuHandle.close(MenuHandle.CloseMode.PLUGIN);
            } catch (RuntimeException | Error closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            closePaths(paths, throwable);
            throw throwable;
        }
    }

    /**
     * 在查看者实体线程关闭已打开的 Window.
     * 先撤销本地可见状态并停掉输入, 再关闭菜单、释放路径、处理 fallback 与关闭回调.
     */
    void closeOnEntity(InventoryCloseEvent.Reason reason, MenuHandle.CloseMode mode) {
        if (!this.open) {
            return;
        }

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
        this.pendingReopenTitle = null;
        this.menuDirty = false;
        this.pendingWindowStates.clear();

        // 资源关闭应尽量完整执行, 最后才重新抛出第一个失败
        Throwable failure = null;
        if (previousTickTask != null) {
            previousTickTask.cancel();
        }
        if (previousMenu != null) {
            try {
                previousMenu.close(mode);
            } catch (RuntimeException | Error throwable) {
                failure = throwable;
            }
        }
        failure = closePaths(previousPaths, failure);

        // 只有玩家主动关闭才进入 fallback, 然后通知关闭处理器
        this.openFallback(reason);
        this.fireCloseHandlers(reason);
        rethrow(failure);
    }

    /**
     * 在调度器退役或插件关闭时回收本地资源.
     * 此路径不发送客户端关闭包, 也不触发 fallback 或用户关闭处理器.
     *
     * @return 退役前是否处于打开状态
     */
    boolean retire() {
        boolean wasOpen = this.open;
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
        this.pendingReopenTitle = null;
        this.menuDirty = false;
        this.pendingWindowStates.clear();

        if (previousTickTask != null) {
            previousTickTask.cancel();
        }
        if (previousMenu != null) {
            try {
                previousMenu.retire();
            } catch (RuntimeException | Error throwable) {
                this.manager.report("Failed to retire Window menu", throwable);
            }
        }
        Throwable failure = closePaths(previousPaths, null);
        if (failure != null) {
            this.manager.report("Failed to retire Window paths", failure);
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
        if (menuHandle.hasInputOverflowed()) {
            this.manager.report(
                    "Closing Window because its incoming packet queue overflowed",
                    new IllegalStateException("incoming packet queue capacity exceeded")
            );
            this.manager.closeAfterProtocolFailure(this);
            return;
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
        this.invalidatePeriodicSlots();
        this.refreshPlayerState();
        this.flush(false, null);
    }

    /**
     * 更新本地标题快照, 并在打开状态下标记下一次 tick 重开容器标题.
     * 多次调用只保留最后一个标题, 避免重复发送 OpenScreen 与全量内容包.
     */
    void updateTitleOnEntity(Component title) {
        this.title = title;
        if (this.open) {
            this.pendingReopenTitle = title;
        }
    }

    /**
     * 在打开状态已经提交后依次运行打开处理器, 单个处理器失败不会阻止后续处理器.
     */
    void fireOpenHandlers() {
        for (int index = 0; index < this.openHandlers.size(); index++) {
            try {
                this.openHandlers.get(index).run();
            } catch (Throwable throwable) {
                this.manager.report("Failed to handle Window open", throwable);
            }
        }
    }

    boolean owns(InventoryView view) {
        return this.menuHandle != null && this.menuHandle.view() == view;
    }

    InventoryView menuView() {
        if (this.menuHandle == null) {
            throw new IllegalStateException("Window menu is not open");
        }
        return this.menuHandle.view();
    }

    void externalClose(InventoryCloseEvent.Reason reason) {
        this.manager.externalClose(this, reason);
    }

    /**
     * 将已通过 generation 筛选的协议输入分派到对应处理流程.
     */
    private void handleInput(MenuInput input) {
        switch (input) {
            case MenuInput.Common common -> this.handleCommonInput(common);
            case MenuInput.WindowSpecific windowSpecific -> this.handleWindowInput(windowSpecific);
        }
    }

    /**
     * 分派所有 Window 都理解的公共协议输入.
     */
    private void handleCommonInput(MenuInput.Common input) {
        switch (input) {
            case MenuInput.Common.Interaction interaction -> this.handleInteraction(interaction);
            case MenuInput.Common.Close close -> this.handleClose(close);
            case MenuInput.Common.BundleSelection selection -> this.handleBundleSelection(selection);
            case MenuInput.Common.Pong pong -> this.handlePong(pong);
        }
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
            case ClickInterpreter.Pending _ -> {}
            case ClickInterpreter.Rejected _ -> this.forceFull = true;
            case ClickInterpreter.SingleClick click -> this.handleSingleClick(click, menu);
            case ClickInterpreter.Drag drag -> this.handleDrag(drag, menu);
        }
    }

    /**
     * 对已解释的单次点击执行 Bukkit 桥接、重入复验和 Item 分派.
     */
    private void handleSingleClick(ClickInterpreter.SingleClick click, MenuHandle menu) {
        long interactionGeneration = this.generation;
        int interactionStateId = menu.stateId();
        if (!this.manager.allowClick(this, click)) {
            return;
        }
        if (!this.isInteractionCurrent(interactionGeneration, menu, interactionStateId)) {
            return;
        }
        if (click.target() instanceof ClickInterpreter.GuiTarget(var windowSlot)) {
            DisplayedSlotPath path = this.requirePath(windowSlot);
            path.handleClick(new ItemClick(
                    click.clickType(),
                    this.viewer,
                    this,
                    windowSlot,
                    click.hotbarButton()
            ));
        } else if (
                click.target() == ClickInterpreter.OutsideTarget.INSTANCE
                        && !this.fireOutsideClick(click)
        ) {
            return;
        }
    }

    /**
     * 对已完成的 QUICK_CRAFT 执行 Bukkit 桥接、重入复验和逐槽 Item 分派.
     */
    private void handleDrag(ClickInterpreter.Drag drag, MenuHandle menu) {
        long interactionGeneration = this.generation;
        int interactionStateId = menu.stateId();
        if (!this.manager.allowDrag(this, drag.clickType(), drag.slots())) {
            return;
        }
        if (!this.isInteractionCurrent(interactionGeneration, menu, interactionStateId)) {
            return;
        }
        for (int index = 0; index < drag.slots().size(); index++) {
            int windowSlot = drag.slots().get(index);
            if (this.layout.route(windowSlot) instanceof WindowLayout.GuiRoute) {
                this.requirePath(windowSlot).handleClick(
                        new ItemClick(this.viewer, drag.clickType(), this, windowSlot)
                );
            }
        }
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
            this.manager.closeFromClient(this, InventoryCloseEvent.Reason.PLAYER);
        } else {
            this.flush(true, this.title);
        }
    }

    private void handleBundleSelection(MenuInput.Common.BundleSelection packet) {
        this.clickInterpreter.reset();
        if (this.menuHandle == null || packet.containerId() != this.menuHandle.containerId()) {
            return;
        }
        if (packet.slot() < 0 || packet.slot() >= this.layout.size() || packet.selectedIndex() < -1) {
            this.forceFull = true;
            return;
        }
        if (this.layout.route(packet.slot()) instanceof WindowLayout.GuiRoute) {
            this.requirePath(packet.slot())
                    .handleBundleSelect(
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
        List<Consumer<Integer>> handlers = this.windowStateChangeHandlers;
        for (int index = 0; index < handlers.size(); index++) {
            try {
                handlers.get(index).accept(this.clientWindowState);
            } catch (Throwable throwable) {
                this.manager.report("Failed to handle Window state acknowledgement", throwable);
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
        boolean auditDue = this.windowTick % AbstractWindow.PLAYER_INVENTORY_AUDIT_INTERVAL == 0;
        if (currentVersion == this.playerInventoryVersion && !auditDue) {
            return;
        }
        this.playerInventoryVersion = currentVersion;
        if (auditDue) {
            this.cursorDirty = true;
        }

        for (int windowSlot = 0; windowSlot < this.layout.size(); windowSlot++) {
            if (this.layout.route(windowSlot) instanceof WindowLayout.PlayerRoute(var inventorySlot)) {
                ItemStack playerItem = this.viewer.getInventory().getItem(inventorySlot);
                if (!AbstractWindow.sameItem(localSlots[windowSlot], playerItem)) {
                    localSlots[windowSlot] = ItemSnapshots.copyOrEmpty(playerItem);
                    this.notifyUpdate(windowSlot);
                }
            }
        }
    }

    /**
     * 汇总 dirty 槽位、光标和标题变化并交给菜单 Adapter 收敛远端状态.
     * 标题变化必须走重开窗口和完整内容同步, 发送失败时保留状态以便下一 tick 重试.
     */
    private void flush(boolean forceFull, @Nullable Component reopenTitle) {
        MenuHandle menu = this.menuHandle;
        ItemStack[] localSlots = this.localSlots;
        DisplayedSlotPath[] paths = this.paths;
        if (!this.open || menu == null || localSlots == null || paths == null) {
            return;
        }

        // 先消费跨线程写入的失效集合, 再在实体线程渲染最终槽位快照
        BitSet dirty = this.takeDirtySlots();
        this.renderDirtySlots(dirty, paths, localSlots);

        Component effectiveReopenTitle = reopenTitle == null ? this.pendingReopenTitle : reopenTitle;
        boolean full = forceFull || this.forceFull || effectiveReopenTitle != null;
        if (dirty.isEmpty() && !this.cursorDirty && !full && !this.menuDirty) {
            return;
        }

        try {
            ItemStack cursor = this.renderCursor();
            if (effectiveReopenTitle != null) {
                menu.updateTitle(effectiveReopenTitle, localSlots, cursor);
            } else {
                menu.synchronize(localSlots, dirty, cursor, this.cursorDirty, full);
            }
            this.cursorDirty = false;
            this.forceFull = false;
            this.menuDirty = false;
            this.pendingReopenTitle = null;
        } catch (RuntimeException | Error throwable) {
            this.cursorDirty = true;
            this.forceFull = true;
            this.menuDirty = true;
            if (effectiveReopenTitle != null) {
                this.pendingReopenTitle = effectiveReopenTitle;
            }
            this.manager.report("Failed to synchronize Window", throwable);
        }
    }

    private ItemStack render(DisplayedSlotPath path, int windowSlot, @Nullable ItemStack fallback) {
        try {
            return ItemSnapshots.copyOrEmpty(path.render());
        } catch (Throwable throwable) {
            this.manager.report("Failed to render Window slot " + windowSlot, throwable);
            return ItemSnapshots.copyOrEmpty(fallback);
        }
    }

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
     * 渲染仅用于协议显示的光标快照.
     * 可视化器失败时回退到真实光标, 因而显示扩展不会破坏容器同步.
     */
    private ItemStack renderCursor() {
        ItemStack actual = ItemSnapshots.copyOrEmpty(this.viewer.getItemOnCursor());
        try {
            ItemProvider visualizer = this.cursorVisualizer.apply(actual.isEmpty() ? null : actual.clone());
            if (visualizer == null) {
                return actual;
            }
            return ItemSnapshots.copyOrEmpty(visualizer.provide(this.cursorRenderContext));
        } catch (Throwable throwable) {
            this.manager.report("Failed to render Window cursor visualizer", throwable);
            return actual;
        }
    }

    private boolean fireOutsideClick(ClickInterpreter.SingleClick click) {
        ClickEvent event = new ClickEvent(this.viewer, click.clickType(), click.hotbarButton());
        List<Consumer<ClickEvent>> handlers = this.outsideClickHandlers;
        for (int index = 0; index < handlers.size(); index++) {
            try {
                handlers.get(index).accept(event);
            } catch (Throwable throwable) {
                this.manager.report("Failed to handle Window outside click", throwable);
            }
        }
        return !event.isCancelled();
    }

    private void refreshTitleOnEntity() {
        this.updateTitleOnEntity(this.refreshTitleValueOnEntity());
    }

    private Component refreshTitleValueOnEntity() {
        Component resolved = Objects.requireNonNull(this.titleSupplier.get(), "title supplier returned null");
        this.title = resolved;
        return resolved;
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

    private DisplayedSlotPath requirePath(int windowSlot) {
        DisplayedSlotPath[] paths = this.paths;
        if (paths == null || paths[windowSlot] == null) {
            throw new IllegalStateException("window slot has no displayed GUI path: " + windowSlot);
        }
        return paths[windowSlot];
    }

    private boolean isInteractionCurrent(long interactionGeneration, MenuHandle interactionMenu, int interactionStateId) {
        return this.open
                && this.generation == interactionGeneration
                && this.menuHandle == interactionMenu
                && interactionMenu.stateId() == interactionStateId;
    }

    private BitSet takeDirtySlots() {
        synchronized (this.dirtyLock) {
            if (this.dirtySlots.isEmpty()) {
                return AbstractWindow.EMPTY_DIRTY_SLOTS;
            }

            // 交换活动与备用缓冲, 使通知线程可以立即继续写入下一批 dirty 槽位
            BitSet dirty = this.dirtySlots;
            this.dirtySlots = this.spareDirtySlots;
            this.dirtySlots.clear();
            this.spareDirtySlots = dirty;
            return dirty;
        }
    }

    private void fireCloseHandlers(InventoryCloseEvent.Reason reason) {
        for (int index = 0; index < this.closeHandlers.size(); index++) {
            try {
                this.closeHandlers.get(index).accept(reason);
            } catch (Throwable throwable) {
                this.manager.report("Failed to handle Window close", throwable);
            }
        }
    }

    private static boolean sameItem(@NotNull ItemStack left, @Nullable ItemStack right) {
        if (right == null || right.isEmpty()) {
            return left.isEmpty();
        }
        return !left.isEmpty() && left.getAmount() == right.getAmount() && left.isSimilar(right);
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

    private static void rethrow(@Nullable Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
    }

    private record PendingWindowState(int state, long createdAtMillis) {
    }
}
