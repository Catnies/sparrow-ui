package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.window.click.WindowOutsideClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.exception.ViewerUnavailableException;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.SlotElement;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.inventory.ClickSemantics;
import net.momirealms.sparrow.ui.inventory.InteractionEdits;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentHolderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsProxy;
import net.momirealms.sparrow.ui.util.*;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 各类 Window 共用的基类, 负责生命周期, 显示路径解析和协议同步.
 * <p>每次打开都会更新 generation, 迟到的协议输入和异步失效通知会被忽略.
 */
abstract class AbstractWindow<M extends MenuHandle> implements Window {
    /**
     * Builder 交给共享生命周期构造器的不可变设置快照.
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
            @NotNull List<Consumer<WindowOutsideClick>> outsideClickHandlers,
            @NotNull Supplier<? extends @Nullable Window> fallbackWindow,
            int windowState,
            @NotNull List<Consumer<Integer>> windowStateChangeHandlers,
            @NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer
    ) {
    }

    private static final int INCOMING_PER_TICK = 128;               // 每 tick 最多处理的入站输入数, 防止单个玩家占满实体线程
    private static final int CURSOR_AUDIT_INTERVAL = 20;            // 光标复核周期(tick), 定期纠正客户端的光标预测
    private static final long PING_TIMEOUT_MILLIS = 30_000;         // 窗口状态 Ping 的超时时间, 超时未收到 Pong 就丢弃
    private static final BitSet EMPTY_DIRTY_SLOTS = new UnmodifiableBitSet(new BitSet());   // 空的 BitSet 脏位槽.

    // 传入的值和选项
    private final WindowManager manager;
    private final Player viewer;
    private final WindowLayout layout;
    private final Object dirtyLock = new Object();      // 保护脏槽位双缓冲的锁
    private final SignalBindings signalBindings = new SignalBindings();   // 本 Window 持有的 Signal 绑定
    private final AtomicLong interactionPathRevision = new AtomicLong(); // InventoryLink 终点或冻结语义的版本
    private final ClickInterpreter clickInterpreter = new ClickInterpreter();       // 把协议点击包解释成点击或拖拽结果
    private final ClickSemantics.Context semanticsContext = new SemanticsContext(); // 点击语义引擎的目标解析与玩家侧 IO
    private final Int2ObjectArrayMap<PendingWindowState> pendingWindowStates = new Int2ObjectArrayMap<>(); // 等待 Pong 确认的窗口状态, Ping id -> 待确认状态
    private final BundleSelectionState[] bundleSelections; // 客户端本地 Bundle 选择, 按协议槽位(raw slot)隔离
    private final boolean[] frozenSlots; // Window 侧的单槽冻结, 覆盖整个路径数组域, 与路径沿途的 GUI 冻结按或合成
    private final RenderContext cursorRenderContext;    // 光标可视化器的渲染上下文
    private final HandlerList<Runnable> openHandlers;   // 打开处理器
    private final HandlerList<Consumer<InventoryCloseEvent.Reason>> closeHandlers;  // 关闭处理器
    private final HandlerList<Consumer<WindowOutsideClick>> outsideClickHandlers;   // 容器外点击处理器
    private final HandlerList<Consumer<Integer>> windowStateChangeHandlers;         // 窗口状态确认处理器

    // 运行时的状态和缓存
    private volatile Component title;   // 最近一次已应用的标题快照
    private volatile Supplier<? extends Component> titleSupplier; // 动态标题来源
    private volatile boolean open;      // Window 是否处于打开状态
    private volatile boolean closeable; // 是否接受客户端主动关闭
    private volatile boolean offhandFrozen; // 是否阻止玩家经此 Window 交换副手
    private volatile long generation;   // 当前打开代际, 用来隔离迟到的输入和通知
    private volatile Supplier<? extends @Nullable Window> fallbackWindow; // 玩家主动关闭后的回退的 Window 来源
    private volatile Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer; // 光标显示转换器
    private volatile int serverWindowState; // 最近一次设置的服务器窗口状态
    private volatile int clientWindowState; // 最近一次收到 Pong 确认的客户端窗口状态

    // 仅允许在玩家实体线程访问的状态和缓存
    private @Nullable M menuHandle;                 // 当前菜单句柄, 关闭时为 null
    private @Nullable DisplayedSlotPath[] paths;    // 每个 Window 槽位的显示路径
    private @Nullable ItemStack[] localSlots;       // 最近一次渲染的 Window 槽位内容
    private @Nullable ScheduledTask tickTask;       // 周期 tick 任务
    private @Nullable MenuHandle.CursorSnapshot localCursor; // 最近一次同步的光标快照
    private @Nullable Component sentTitle;          // 最近一次成功进入发送流程的标题
    private BitSet dirtySlots;      // 活动脏槽位缓冲, 任意线程的通知都可以写入
    private BitSet spareDirtySlots; // 备用脏槽位缓冲, 与活动缓冲交换复用
    private final BitSet renderedBeforeEvent = new BitSet(); // 本 tick 已在 Bukkit 事件前渲染, 仍待最终同步的槽位
    private long windowTick;        // 本次打开以来的 tick 计数
    private boolean cursorDirty;    // 光标是否需要重新核对
    private boolean forceFull;      // 下一次同步是否强制全量
    private boolean menuDirty;      // 菜单是否有槽位内容之外的待同步状态
    private boolean titleDirty;     // 标题是否待重开
    private boolean forceReopen;    // 即使标题相同也必须重开菜单

    /**
     * 根据设置创建 Window.
     *
     * @param manager Window 管理器
     * @param viewer 查看者
     * @param layout 预编译的窗口布局
     * @param settings Builder 整理好的行为设置
     */
    AbstractWindow(@NotNull WindowManager manager, @NotNull Player viewer, @NotNull WindowLayout layout, @NotNull Settings settings) {
        this.manager = manager;
        this.viewer = viewer;
        this.layout = layout;
        this.bundleSelections = new BundleSelectionState[layout.protocolSize()];
        this.frozenSlots = new boolean[layout.size()];
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

    /**
     * 更新本地标题快照.
     *
     * @param title 新标题
     */
    void notifyUpdateTitle(Component title) {
        this.title = title;
        if (this.open) {
            this.titleDirty = !Objects.equals(this.sentTitle, title);
        }
    }

    /**
     * 重新读一次标题 supplier 并发布到本地快照, 已打开时安排重开标题.
     */
    void notifyUpdateTitle() {
        this.notifyUpdateTitle(this.refreshTitle());
    }

    /**
     * 重新读一次标题 supplier 并写进本地快照, supplier 返回 null 时按空标题处理.
     *
     * @return 新标题
     */
    private Component refreshTitle() {
        Component component = this.titleSupplier.get();
        this.title = component != null ? component : Component.empty();
        return this.title;
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
    public boolean frozenAt(int windowSlot) {
        Objects.checkIndex(windowSlot, this.frozenSlots.length);
        return this.frozenSlots[windowSlot];
    }

    @Override
    public void frozenAt(int windowSlot, boolean frozen) {
        Objects.checkIndex(windowSlot, this.frozenSlots.length);
        this.submit(
                () -> {
                    this.frozenSlots[windowSlot] = frozen;
                    this.notifyInteractionPathChanged();
                },
                "Failed to update Window slot frozen state"
        );
    }

    @Override
    public boolean offhandFrozen() {
        return this.offhandFrozen;
    }

    @Override
    public void offhandFrozen(boolean frozen) {
        this.submit(
                () -> {
                    this.offhandFrozen = frozen;
                    this.notifyInteractionPathChanged();
                },
                "Failed to update Window offhand frozen state"
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
        List<Consumer<InventoryCloseEvent.Reason>> copy = HandlerList.copyConsumers(closeHandlers);
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
        Consumer<InventoryCloseEvent.Reason> handler = HandlerList.narrowConsumer(closeHandler);
        this.submit(
                () -> this.closeHandlers.append(handler),
                "Failed to add Window close handler"
        );
    }

    @Override
    @NotNull
    public Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super Window> callback) {
        Objects.requireNonNull(callback, "callback");
        return this.signalBindings.add(signal.onDirtyWeak(() -> callback.accept(this)));
    }

    @Override
    public void removeCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler) {
        this.submit(
                () -> this.closeHandlers.remove(HandlerList.narrowConsumer(closeHandler)),
                "Failed to remove Window close handler"
        );
    }

    @Override
    public void setOutsideClickHandlers(@NotNull List<? extends Consumer<? super WindowOutsideClick>> outsideClickHandlers) {
        List<Consumer<WindowOutsideClick>> copy = HandlerList.copyConsumers(outsideClickHandlers);
        this.submit(
                () -> this.outsideClickHandlers.set(copy),
                "Failed to replace Window outside click handlers"
        );
    }

    @NotNull
    @Override
    public List<Consumer<WindowOutsideClick>> getOutsideClickHandlers() {
        return this.outsideClickHandlers.snapshot();
    }

    @Override
    public void addOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler) {
        Consumer<WindowOutsideClick> handler = HandlerList.narrowConsumer(outsideClickHandler);
        this.submit(
                () -> this.outsideClickHandlers.append(handler),
                "Failed to add Window outside click handler"
        );
    }

    @Override
    public void removeOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler) {
        this.submit(
                () -> this.outsideClickHandlers.remove(HandlerList.narrowConsumer(outsideClickHandler)),
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

    /**
     * 更新服务器窗口状态, 并给客户端发一个独立 Ping.
     * Ping id 随机生成并暂存状态, 收到同 id 的 Pong 才推进客户端状态快照.
     *
     * @param windowState 新的服务器窗口状态
     */
    private void updateWindowStateOnEntity(int windowState) {
        this.serverWindowState = windowState;
        MenuHandle menu = this.menuHandle;
        if (!this.open || menu == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 顺手清掉超时未确认的待确认状态
        this.pendingWindowStates.entrySet().removeIf(
                entry -> now - entry.getValue().createdAtMillis() > PING_TIMEOUT_MILLIS
        );
        // 生成一个没被占用的 Ping id
        int pingId;
        do {
            pingId = ThreadLocalRandom.current().nextInt();
        } while (this.pendingWindowStates.containsKey(pingId));
        this.pendingWindowStates.put(pingId, new PendingWindowState(windowState, now));
        menu.sendPing(pingId);
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
        List<Consumer<Integer>> copy = HandlerList.copyConsumers(handlers);
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
        Consumer<Integer> copied = HandlerList.narrowConsumer(handler);
        this.submit(
                () -> this.windowStateChangeHandlers.append(copied),
                "Failed to add Window state handler"
        );
    }

    @Override
    public void removeWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        this.submit(
                () -> this.windowStateChangeHandlers.remove(HandlerList.narrowConsumer(handler)),
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

    // 标记菜单有槽位内容之外的待同步状态, 例如切石机同步配方 (由具体 Window 类型定义).
    protected final void notifyUpdateMenu() {
        this.menuDirty = true;
    }

    // 请求用当前标题重开菜单, 用于标题之外的客户端状态恢复.
    protected final void notifyReopen() {
        if (this.open) {
            this.forceReopen = true;
        }
    }

    @NotNull
    @Override
    public ItemStack displayedAt(int windowSlot) {
        ItemStack[] localSlots = this.localSlots;
        if (localSlots == null || windowSlot < 0 || windowSlot >= localSlots.length) {
            return ItemStack.empty();
        }
        ItemStack displayed = localSlots[windowSlot];
        return displayed == null ? ItemStack.empty() : displayed.clone();
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
    public Gui lowerGui() {
        return this.layout.lowerGui();
    }

    @NotNull
    @Override
    public List<Gui> guis() {
        return this.layout.guis();
    }

    @NotNull
    @Override
    public SlotElement.GuiLink guiAt(int windowSlot) {
        return this.layout.guiAt(windowSlot);
    }

    @NotNull
    @Override
    public SlotElement.GuiLink guiAtHotbar(int hotbarSlot) {
        return this.layout.guiAt(this.layout.windowSlotAtHotbar(hotbarSlot));
    }

    @Override
    public int windowSlotAtHotbar(int hotbarSlot) {
        return this.layout.windowSlotAtHotbar(hotbarSlot);
    }

    @NotNull
    @Override
    public CompletionStage<OpenResult> open() {
        return this.manager.open(this);
    }

    /**
     * 在玩家的实体线程打开 Window, 中途失败就按相反方向回滚.
     *
     * @param generation 本次打开代际
     * @param replacingWindow 是否正在替换同一玩家的 Window
     */
    void openOnViewerEntity(long generation, boolean replacingWindow) {
        // 初始化本次打开的 generation 相关状态
        this.generation = generation;
        this.windowTick = 0;
        this.cursorDirty = true;
        this.forceFull = true;
        this.menuDirty = true;
        this.titleDirty = false;
        this.forceReopen = false;
        this.sentTitle = null;
        this.clickInterpreter.reset();
        Arrays.fill(this.bundleSelections, null);
        this.pendingWindowStates.clear();
        synchronized (this.dirtyLock) {
            this.dirtySlots.clear();
            this.spareDirtySlots.clear();
        }
        this.renderedBeforeEvent.clear();
        this.refreshTitle();

        // 先在局部变量中构建资源, 避免半初始化状态对 tick 任务可见
        M menuHandle = this.createMenuHandle(this.manager.menuFactory(), generation);
        DisplayedSlotPath[] paths = new DisplayedSlotPath[this.layout.size()];
        ItemStack[] localSlots = new ItemStack[this.layout.size()];
        ScheduledTask tickTask = null;
        boolean menuOpening = false;

        try {
            for (int windowSlot = 0; windowSlot < this.layout.size(); windowSlot++) {
                SlotElement.GuiLink link = this.layout.guiAt(windowSlot);
                paths[windowSlot] = new DisplayedSlotPath(this, windowSlot, link.gui(), link.slot());
            }

            // build 可以发生在任意线程; 首帧渲染前在 viewer 实体线程刷新实际连接的 ReferencingInventory
            this.refreshLinkedInventories(paths);
            // 构造路径会标记初始 dirty; 全部路径就绪后统一渲染一次, 后续到达的通知留给首个 tick
            this.renderDirtySlots(this.takeDirtySlots(), paths, localSlots);
            this.prepareVirtualContent(menuHandle, localSlots);

            // 安排周期 tick, 再发送初始完整状态, 两者都成功才确认发布菜单已打开的状态
            tickTask = this.manager.startTick(this);
            if (tickTask == null) {
                throw new ViewerUnavailableException();
            }
            menuOpening = true;
            menuHandle.prepareOpen(replacingWindow);
            MenuHandle.CursorSnapshot localCursor = this.renderCursor(menuHandle.cursor());
            Component title = this.title;
            menuHandle.open(title, this.protocolSlots(localSlots), localCursor);

            this.menuHandle = menuHandle;
            this.paths = paths;
            this.localSlots = localSlots;
            this.localCursor = localCursor;
            this.sentTitle = title;
            this.tickTask = tickTask;
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
                if (menuOpening) {
                    menuHandle.close(InventoryCloseEvent.Reason.PLUGIN);
                } else {
                    menuHandle.retire();
                }
            } catch (RuntimeException | Error closeFailure) {
                ThrowableUtils.combine(throwable, closeFailure);
            }
            closePaths(paths, throwable);
            throw throwable;
        }
    }

    // 创建与 Window 类型对应的菜单处理器.
    @NotNull
    protected abstract M createMenuHandle(@NotNull MenuFactory factory, long generation);

    // 运行打开处理器.
    void fireOpenHandlers() {
        this.openHandlers.forEachIsolated(Runnable::run, "Failed to handle Window open", this.manager::report);
    }

    /**
     * 执行一次玩家实体 tick
     * 先限量处理协议输入, 再做周期刷新和批量同步.
     *
     * @param task 触发本次 tick 的调度任务
     */
    void tick(ScheduledTask task) {
        M menuHandle = this.menuHandle;
        if (!this.open || menuHandle == null) {
            return;
        }

        // PacketHandler 在 Paper 的限流器之前, 所以这里要自己限制包速率, 防止恶意刷包
        // 溢出后按 UNKNOWN 原因强制关闭 Window;
        if (menuHandle.hasInputOverflowed()) {
            this.manager.report(
                    "Closing Window because its incoming packet queue overflowed",
                    new IllegalStateException("incoming packet queue capacity exceeded")
            );
            this.manager.closeNow(this, InventoryCloseEvent.Reason.UNKNOWN);
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
        // 刷新连接的 Inventory: ReferencingInventory 把 Bukkit 容器变更同步进镜像并生成 External 事件, 其他 Inventory 不处理.
        // 因为刷新不是点击语义, 所以 GUI 冻结槽和 Window 虚拟槽位连接的 Inventory 也要同步.
        this.refreshLinkedInventories(this.paths);
        // 定时标脏光标物品.
        if (this.windowTick % CURSOR_AUDIT_INTERVAL == 0) {
            this.cursorDirty = true;
        }
        this.flush(false);
    }

    /**
     * 把已经通过 generation 筛选的协议输入分给对应的处理流程.
     *
     * @param input 待分发的协议输入
     */
    private void handleInput(MenuInput input) {
        switch (input) {
            case MenuInput.Common.Interaction interaction ->    this.handleInteraction(interaction);
            case MenuInput.Common.Close close ->                this.handleClose(close);
            case MenuInput.Common.BundleSelection selection ->  this.handleBundleSelection(selection);
            case MenuInput.Common.Pong pong ->                  this.handlePong(pong);
            case MenuInput.WindowSpecific windowSpecific ->     this.handleWindowInput(windowSpecific);
        }
    }

    /**
     * 校验交互是否属于当前容器状态, 解释成点击或拖拽后分发给 GUI 或容器外处理器.
     * 不可信的输入会强制全量恢复; 正常的输入只复核客户端预测碰过的槽位.
     *
     * @param interaction 待处理的交互
     */
    private void handleInteraction(MenuInput.Common.Interaction interaction) {
        M menu = this.menuHandle;
        // 不属于当前状态的输入: 重置解释器, 强制全量恢复
        if (menu == null || !menu.accepts(interaction)) {
            this.clickInterpreter.reset();
            this.forceFull = true;
            return;
        }
        this.cursorDirty = true;
        ClickInterpreter.Result result = this.clickInterpreter.interpret(interaction, this.layout, this.generation);
        switch (result) {
            case ClickInterpreter.Result.Pending ignoredPending -> {}
            case ClickInterpreter.Result.Rejected ignoredRejection ->   this.forceFull = true;
            case ClickInterpreter.Result.SingleClick click ->           this.handleSingleClick(click, menu);
            case ClickInterpreter.Result.Drag drag ->                   this.handleDrag(drag, menu);
        }
    }

    /**
     * 处理一次已经解释好的单击: Inventory 槽位先形成精确候选, 再依次派发 Bukkit 和 Sparrow 事件并提交.
     * <p>无论是否算出候选, 每一次点击都会派发一次 Bukkit 事件; Item 和空槽位仍按原有顺序先过 Bukkit 事件再分派.
     * 语义动过的槽位已经标脏, 客户端预测会在同一 tick 的 flush 中被服务端渲染结果纠正.
     *
     * @param click 解释好的单击
     * @param menu 当前菜单
     */
    private void handleSingleClick(ClickInterpreter.Result.SingleClick click, MenuHandle menu) {
        int rawSlot = click.rawSlot();
        // 如果 Window 冻结了副手, 则不处理.
        if (click.clickType() == ClickType.SWAP_OFFHAND && this.offhandFrozen) {
            this.notifyUpdate(rawSlot);
            return;
        }
        ClickGuard guard = new ClickGuard(menu, click);
        if (rawSlot != InventoryView.OUTSIDE) {
            DisplayedSlotPath path = this.requirePath(rawSlot);
            // Inventory 槽位先给语义引擎; 引擎不接管的(Item/空槽)走普通 Item 分派
            BundleSelectionState bundleSelection = this.bundleSelections[rawSlot];
            boolean handled = ClickSemantics.handleClick(
                    this.semanticsContext,
                    click.clickType(),
                    click.hotbarButton(),
                    rawSlot,
                    bundleSelection == null ? null : bundleSelection.observedBundle(),
                    bundleSelection == null ? -1 : bundleSelection.selectedIndex(),
                    () -> this.bundleSelections[rawSlot] = null,
                    guard
            );
            if (handled) return;

            InventoryAction action = ClickSemantics.estimateInventoryAction(
                    this.semanticsContext,
                    click.clickType(),
                    click.hotbarButton(),
                    rawSlot
            );
            if (!guard.refreshEventView() || !guard.allowBukkitClick(click, action) || !guard.stillValid()) {
                return;
            }
            path.handleClick(new ItemClick(click.clickType(), this.viewer, this, menu.cursor(), rawSlot, click.hotbarButton()));
            return;
        }

        InventoryAction action = ClickSemantics.estimateInventoryAction(
                this.semanticsContext,
                click.clickType(),
                click.hotbarButton(),
                rawSlot
        );
        if (!guard.refreshEventView() || !guard.allowBukkitClick(click, action) || !guard.stillValid()) {
            return;
        }
        // 容器外点击: 先通知外部处理器, 未取消再交给语义引擎
        WindowOutsideClick event = new WindowOutsideClick(this.viewer, this, click.clickType(), menu.cursor(), click.hotbarButton());
        this.outsideClickHandlers.forEachIsolated(
                handler -> handler.accept(event),
                "Failed to handle Window outside click",
                this.manager::report
        );
        if (event.isCancelled() || !guard.stillValid()) {
            return;
        }
        ClickSemantics.handleOutsideClick(this.semanticsContext, click.clickType());
    }

    /**
     * 处理一次完成的 QUICK_CRAFT 拖拽: 先规划并按放入规则过滤, 再把真实分配候选交给 Bukkit 事件,
     * 最后把手势本身通知给途经的每个 Item.
     * <p>未取消时, 参与的 Inventory 槽位构成一个事务; Item 槽位不参与分配.
     * <p>Item 通知只看手势成不成立: 引擎是否接管, 分配是否被放入规则全部过滤, Bukkit 事件是否被取消, 都不影响它.
     *
     * @param drag 解释好的拖拽
     * @param menu 当前菜单
     */
    private void handleDrag(ClickInterpreter.Result.Drag drag, MenuHandle menu) {
        // 引擎会提交事务并扣减光标, 手势光标必须在它之前快照
        ItemStack cursor = menu.cursor();
        ClickSemantics.handleDrag(
                this.semanticsContext,
                drag.clickType(),
                drag.slots(),
                new DragGuard(menu, drag.clickType())
        );
        this.dispatchItemDragClick(drag, cursor);
    }

    // 把拖拽手势通知给途经的每个 Item. 手势本身不成立时不打扰 Item: 空光标没有东西可分发,
    // 非创造模式的中键拖拽在原版语义里不存在. 两个条件与 ClickPlanner 判定拖拽候选时一致.
    private void dispatchItemDragClick(ClickInterpreter.Result.Drag drag, ItemStack cursor) {
        if (cursor.isEmpty() || (drag.clickType() == ClickType.MIDDLE && this.viewer.getGameMode() != GameMode.CREATIVE)) {
            return;
        }

        List<Integer> windowSlots = drag.slots();
        List<ItemDragClick.Stop> path = new ArrayList<>(windowSlots.size());
        for (int index = 0; index < windowSlots.size(); index++) {
            int windowSlot = windowSlots.get(index);
            path.add(new ItemDragClick.Stop(windowSlot, this.requirePath(windowSlot).kind()));
        }
        for (int index = 0; index < path.size(); index++) {
            ItemDragClick.Stop stop = path.get(index);
            if (stop.kind() != ItemDragClick.Kind.ITEM) {
                continue;
            }
            this.requirePath(stop.windowSlot()).handleDrag(
                    new ItemDragClick(drag.clickType(), this.viewer, this, cursor, stop.windowSlot(), path)
            );
        }
    }

    // 让同一 tick 的下一个 Bukkit 事件看见刚提交的服务端渲染结果, 而不是上一个监听器改过的 Bukkit 事件状态副本.
    private void resetBukkitEventView(MenuHandle menu) {
        DisplayedSlotPath[] paths = this.paths;
        ItemStack[] localSlots = this.localSlots;
        if (paths == null || localSlots == null) {
            return;
        }
        // 消费掉失效标记, 本轮最终同步不再重复渲染这些槽位, 只把它们留在待同步集合里
        BitSet pending = this.takeDirtySlots();
        this.renderDirtySlots(pending, paths, localSlots);
        this.renderedBeforeEvent.or(pending);
        menu.resetBukkitEventView(this.protocolSlots(localSlots), pending, menu.cursor());
    }

    /**
     * 处理客户端发来的关闭包.
     * 不允许关闭的 Window 会立刻用当前标题和全量内容重新打开, 而不是交给 Bukkit 的外部关闭处理器否决.
     *
     * @param packet 关闭包
     */
    private void handleClose(MenuInput.Common.Close packet) {
        // 不是当前容器的关闭包, 直接忽略
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
     * 处理客户端的收纳袋选择包.
     * 选择只转发给 GUI 槽位, 不同步客户端已经维护的本地选择状态.
     *
     * @param packet 收纳袋选择包
     */
    private void handleBundleSelection(MenuInput.Common.BundleSelection packet) {
        this.clickInterpreter.reset();
        // 不是当前容器的包, 直接忽略
        if (this.menuHandle == null || packet.containerId() != this.menuHandle.containerId()) {
            return;
        }
        // 槽位或选择索引不合法: 强制全量纠正
        if (packet.slot() < 0 || packet.slot() >= this.layout.protocolSize() || packet.selectedIndex() < -1) {
            this.forceFull = true;
            return;
        }
        this.updateBundleSelection(packet.slot(), packet.selectedIndex());
        this.requirePath(packet.slot()).handleBundleSelect(new BundleSelectClick(this.viewer, this, packet.slot(), packet.selectedIndex()));
    }

    /**
     * 记录客户端在某个协议槽位(raw slot)看到的 Bundle 与实际选择.
     * 重复选择同一项, 取消选择或越界索引都按原版规则清除.
     *
     * @param rawSlot Bundle 所在的协议槽位(raw slot)
     * @param requestedIndex 客户端请求切换到的内部索引
     */
    private void updateBundleSelection(int rawSlot, int requestedIndex) {
        ItemStack[] localSlots = this.localSlots;
        if (localSlots == null) {
            return;
        }
        ItemStack bundle = localSlots[rawSlot];
        // 空槽必然不是收纳袋, 先挡掉再查 NMS 物品类型
        if (ItemUtils.isNullOrEmpty(bundle) || !ItemUtils.isType(bundle, ItemsProxy.BUNDLE)) {
            this.bundleSelections[rawSlot] = null;
            return;
        }
        Object contents = DataComponentHolderProxy.INSTANCE.component(
                ItemUtils.getItemStackHandle(bundle),
                DataComponentsProxy.BUNDLE_CONTENTS
        );
        BundleSelectionState previous = this.bundleSelections[rawSlot];
        if (contents == null
                || requestedIndex < 0
                || requestedIndex >= BundleContentsProxy.INSTANCE.size(contents)
                || previous != null && previous.selectedIndex() == requestedIndex && previous.observedBundle().equals(bundle)) {
            this.bundleSelections[rawSlot] = null;
            return;
        }
        this.bundleSelections[rawSlot] = new BundleSelectionState(bundle.clone(), requestedIndex);
    }

    /**
     * 收到客户端 Pong: 找到对应的待确认窗口状态, 推进客户端状态并通知确认处理器.
     *
     * @param packet Pong 包
     */
    private void handlePong(MenuInput.Common.Pong packet) {
        PendingWindowState pending = this.pendingWindowStates.remove(packet.id());
        if (pending == null) return;

        this.clientWindowState = pending.state();
        this.windowStateChangeHandlers.forEachIsolated(
                handler -> handler.accept(this.clientWindowState),
                "Failed to handle Window state acknowledgement",
                this.manager::report
        );
    }

    /**
     * 处理特殊 Window 类型的协议输入.
     *
     * @param input Window 专属输入
     */
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
    }

    /**
     * 让某个槽位上当前显示的 Item 直接处理这次点击.
     * <p>这条路不经过 Bukkit 的 InventoryClickEvent;
     * GUI 冻结, 背景和空路径仍按 {@link DisplayedSlotPath} 的普通 Item 规则决定是否分发.
     *
     * @param windowSlot Window 槽位
     * @param clickType 点击类型
     */
    protected final void dispatchItemClick(int windowSlot, @NotNull ClickType clickType) {
        this.requirePath(windowSlot).handleClick(new ItemClick(clickType, this.viewer, this, this.semanticsContext.cursor(), windowSlot, -1));
    }

    /**
     * 返回指定 Window 槽位的显示路径.
     *
     * @param windowSlot Window 槽位
     * @return 显示路径
     * @throws IllegalStateException 槽位没有显示路径时抛出
     */
    private DisplayedSlotPath requirePath(int windowSlot) {
        DisplayedSlotPath[] paths = this.paths;
        if (paths == null || paths[windowSlot] == null) {
            throw new IllegalStateException("window slot has no displayed GUI path: " + windowSlot);
        }
        return paths[windowSlot];
    }

    /**
     * 确认候选经过事件或 Pre 后仍属于当前交互.
     * 处理器可能已经关闭或重开 Window, 改变菜单状态, 或替换任一协议槽位的 InventoryLink / 冻结语义;
     * 任一条件变化都会让旧候选作废, 不重新规划.
     *
     * @param interactionGeneration 交互开始时的 generation
     * @param interactionMenu 交互开始时的菜单
     * @param interactionStateId 交互开始时的 state id
     * @param interactionPathRevision 交互开始时的路径语义版本
     * @return 交互仍然有效时返回 true
     */
    private boolean isInteractionCurrent(
            long interactionGeneration,
            MenuHandle interactionMenu,
            int interactionStateId,
            long interactionPathRevision
    ) {
        if (!(this.open
                && this.generation == interactionGeneration
                && this.menuHandle == interactionMenu
                && interactionMenu.stateId() == interactionStateId)) {
            return false;
        }
        if (this.interactionPathRevision.get() != interactionPathRevision) {
            return false;
        }
        DisplayedSlotPath[] paths = this.paths;
        if (paths == null) {
            return false;
        }
        // 强制处理每条路径攒下的 GUI 结构变化, 让下面的复核看到最新的交互终点与冻结语义.
        // 没有待处理变化的路径只是读一次自己的状态, 不会重新解析.
        for (int windowSlot = 0; windowSlot < this.layout.protocolSize(); windowSlot++) {
            paths[windowSlot].refreshInteractionState();
        }
        return this.open
                && this.generation == interactionGeneration
                && this.menuHandle == interactionMenu
                && interactionMenu.stateId() == interactionStateId
                && this.interactionPathRevision.get() == interactionPathRevision;
    }

    // DisplayedSlotPath 在重新解析后发现 InventoryLink 终点或冻结语义改变时调用.
    void notifyInteractionPathChanged() {
        this.interactionPathRevision.incrementAndGet();
    }

    /**
     * 对指定路径数组里的连接Inventory逐一刷新, 同一个Inventory只刷新一次.
     *
     * @param paths 显示路径, 为 null 时不做任何事
     */
    private void refreshLinkedInventories(@Nullable DisplayedSlotPath[] paths) {
        LinkedHashSet<SparrowInventory> seen = new LinkedHashSet<>();
        this.forEachLinkedInventory(paths, false, link -> {
            SparrowInventory inventory = link.inventory();
            if (seen.add(inventory)) {
                inventory.refresh();
            }
        });
    }

    /**
     * 遍历指定路径数组终点的 InventoryLink.
     *
     * @param paths 显示路径, 为 null 时不做任何事
     * @param semanticOnly 是否只遍历参与点击的连接(跳过 GUI 冻结槽与 Window 虚拟槽位)
     * @param action 对每个终点连接执行的操作
     */
    private void forEachLinkedInventory(
            @Nullable DisplayedSlotPath[] paths,
            boolean semanticOnly,
            @NotNull Consumer<SlotElement.InventoryLink> action
    ) {
        if (paths == null) return;
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
                action.accept(link);
            }
        }
    }

    // 把 Window 虚拟区域的渲染结果写进具体菜单的状态.
    protected void prepareVirtualContent(@NotNull M menuHandle, ItemStack @NotNull [] logicalSlots) {
    }

    // 把脏槽位, 光标和标题变化汇总, 交给菜单处理器同步给客户端.
    private void flush(boolean forceReopen) {
        M menu = this.menuHandle;
        ItemStack[] localSlots = this.localSlots;
        DisplayedSlotPath[] paths = this.paths;
        if (!this.open || menu == null || localSlots == null || paths == null) {
            return;
        }
        this.forceReopen |= forceReopen;

        // 先消费跨线程写入的失效集合, 再在实体线程生成本轮 Window 槽位渲染结果
        BitSet dirty = this.takeDirtySlots();
        this.renderDirtySlots(dirty, paths, localSlots);
        // Bukkit 事件前已经渲染过的槽位不再重复渲染, 但仍要参与本轮同步
        if (!this.renderedBeforeEvent.isEmpty()) {
            this.renderedBeforeEvent.or(dirty);
            dirty = this.renderedBeforeEvent;
        }

        // Window 虚拟槽位的变化单独写入菜单状态, 不进入协议槽位(raw slot)
        boolean virtualDirty = dirty.nextSetBit(this.layout.protocolSize()) >= 0;
        boolean reopen = this.forceReopen || this.titleDirty;
        boolean full = this.forceFull || reopen;
        if (dirty.isEmpty() && !this.cursorDirty && !full && !this.menuDirty) {
            return;
        }

        try {
            // Window 虚拟槽位变化或全量刷新时重写虚拟内容
            if (virtualDirty || full) {
                this.prepareVirtualContent(menu, localSlots);
            }
            // Window 虚拟槽位不属于协议, 发送前从脏集合里清掉
            if (virtualDirty) {
                dirty.clear(this.layout.protocolSize(), dirty.length());
            }
            ItemStack[] protocolSlots = this.protocolSlots(localSlots);
            MenuHandle.CursorSnapshot cursor = this.localCursor;
            if (cursor == null || this.cursorDirty) {
                cursor = this.renderCursor(menu.cursor());
            }
            if (reopen) {
                Component title = this.title;
                menu.reopenWithTitle(title, protocolSlots, cursor);
                this.sentTitle = title;
            } else {
                menu.synchronize(protocolSlots, dirty, cursor, this.cursorDirty, full);
            }
            this.localCursor = cursor;
            this.cursorDirty = false;
            this.forceFull = false;
            this.menuDirty = false;
            this.titleDirty = false;
            this.forceReopen = false;
        } catch (RuntimeException | Error throwable) {
            this.cursorDirty = true;
            this.forceFull = true;
            this.menuDirty = true;
            this.forceReopen |= reopen; // 重开失败后客户端标题状态未知, 后续必须再重开一次
            this.manager.report("Failed to synchronize Window", throwable);
        } finally {
            this.renderedBeforeEvent.clear();
        }
    }

    // 取出脏槽位, 并立刻清空缓冲, 让通知线程可以继续写下一批.
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

    // 把脏槽位重新渲染进本地快照, 失败时上报并退回上一份本地快照.
    private void renderDirtySlots(BitSet dirty, DisplayedSlotPath[] paths, ItemStack[] localSlots) {
        for (
                int windowSlot = dirty.nextSetBit(0);
                windowSlot >= 0;
                windowSlot = dirty.nextSetBit(windowSlot + 1)
        ) {
            DisplayedSlotPath path = paths[windowSlot];
            if (path != null) {
                try {
                    ItemStack rendered = path.render();
                    if (windowSlot < this.bundleSelections.length) {
                        BundleSelectionState selection = this.bundleSelections[windowSlot];
                        if (selection != null && !selection.observedBundle().equals(rendered)) {
                            this.bundleSelections[windowSlot] = null;
                        }
                    }
                    localSlots[windowSlot] = rendered;
                } catch (Throwable throwable) {
                    this.manager.report("Failed to render Window slot " + windowSlot, throwable);
                    localSlots[windowSlot] = localSlots[windowSlot] == null ? ItemStack.empty() : localSlots[windowSlot];
                }
            }
        }
    }

    // 截取容器数据包所需的协议槽位(raw slot)部分.
    @NotNull
    private ItemStack[] protocolSlots(ItemStack @NotNull [] logicalSlots) {
        if (logicalSlots.length == this.layout.protocolSize()) {
            return logicalSlots;
        }
        return Arrays.copyOf(logicalSlots, this.layout.protocolSize());
    }

    // 渲染只给客户端看的光标物品副本, 如失败则回退到菜单实际光标.
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

    @NotNull
    @Override
    public CompletionStage<CloseResult> close() {
        return this.manager.close(this);
    }

    // 关闭已打开的 Window.
    boolean closeOnViewerEntity(InventoryCloseEvent.Reason reason) {
        if (!this.open) return false;

        Throwable failure = this.teardownOnEntity(reason);
        // 只有玩家主动关闭才进入 fallback
        if (reason == InventoryCloseEvent.Reason.PLAYER) {
            this.openFallback();
        }
        this.fireCloseHandlers(reason);
        ThrowableUtils.throwIfUnchecked(failure);
        return true;
    }

    /**
     * 服务器已经接管关闭流程后, 只回收 Window 的本地资源并通知关闭处理器.
     * 该路径不再次主动关闭容器, 也不触发 Fallback Window.
     *
     * @param reason Bukkit 容器关闭原因
     * @return 是否关闭了一个打开的 Window
     */
    boolean closeAfterInventoryEvent(InventoryCloseEvent.Reason reason) {
        if (!this.open) return false;

        Throwable failure = this.teardownOnEntity(null);
        this.fireCloseHandlers(reason);
        ThrowableUtils.throwIfUnchecked(failure);
        return true;
    }

    // 关闭本次打开的菜单, 并清理菜单, tick 任务和显示路径.
    // reason 为 null 表示容器关闭已由 Paper 或 调度器注销任务 接管.
    @Nullable
    private Throwable teardownOnEntity(@Nullable InventoryCloseEvent.Reason reason) {
        // 使后续输入与 tick 立即失效
        this.open = false;
        this.generation++;
        this.clickInterpreter.reset();
        Arrays.fill(this.bundleSelections, null);

        ScheduledTask previousTickTask = this.tickTask;
        M previousMenu = this.menuHandle;
        DisplayedSlotPath[] previousPaths = this.paths;
        this.tickTask = null;
        this.menuHandle = null;
        this.paths = null;
        this.localSlots = null;
        this.localCursor = null;
        this.sentTitle = null;
        this.menuDirty = false;
        this.titleDirty = false;
        this.forceReopen = false;
        this.pendingWindowStates.clear();

        // 单项清理失败不能阻止剩余资源释放或关闭处理器
        Throwable failure = null;
        if (previousTickTask != null) {
            try {
                previousTickTask.cancel();
            } catch (Throwable throwable) {
                failure = throwable;
            }
        }
        if (previousMenu != null) {
            try {
                if (reason == null) {
                    previousMenu.retire();
                } else {
                    previousMenu.close(reason);
                }
            } catch (Throwable throwable) {
                failure = ThrowableUtils.combine(failure, throwable);
            }
        }
        return closePaths(previousPaths, failure);
    }

    // 运行关闭处理器.
    private void fireCloseHandlers(InventoryCloseEvent.Reason reason) {
        this.closeHandlers.forEachIsolated(
                handler -> handler.accept(reason),
                "Failed to handle Window close",
                this.manager::report
        );
    }

    // 玩家主动关闭后, 解析并打开后备 Window.
    private void openFallback() {
        try {
            Window fallback = this.fallbackWindow.get();
            if (fallback != null) {
                fallback.open().exceptionally(throwable -> {
                    this.manager.report("Failed to open Window fallback", throwable);
                    return null;
                });
            }
        } catch (Throwable throwable) {
            this.manager.report("Failed to resolve or open Window fallback", throwable);
        }
    }

    // 调度器意外退役时回收本地资源, 不发客户端关闭包, 也不调用用户关闭处理器.
    boolean retireSession() {
        boolean wasOpen = this.open;
        Throwable failure = this.teardownOnEntity(null);
        if (failure != null) {
            this.manager.report("Failed to retire Window session", failure);
        }
        return wasOpen;
    }

    /**
     * 判断一个 Bukkit InventoryView 是不是当前菜单提供的.
     *
     * @param view Bukkit InventoryView
     * @return 属于当前菜单时返回 true
     */
    boolean ownsInventoryView(InventoryView view) {
        return this.menuHandle != null && this.menuHandle.view() == view;
    }

    /**
     * 返回当前菜单的 Bukkit InventoryView.
     *
     * @return Bukkit 事件用 InventoryView
     * @throws IllegalStateException 菜单未打开时抛出
     */
    InventoryView inventoryView() {
        if (this.menuHandle == null) {
            throw new IllegalStateException("Window menu is not open");
        }
        return this.menuHandle.view();
    }

    /**
     * 把一个不需要返回结果的命令排到玩家的实体线程执行.
     * 执行或调度失败会统一上报, 玩家实体不可用后命令会被忽略.
     *
     * @param action 要执行的命令
     * @param failureMessage 失败时的报告文本
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
     * 把一个需要返回结果的命令排到玩家的实体线程执行.
     *
     * @param action 玩家仍可调度时执行的操作
     * @param retiredAction 玩家实体不可用后执行的替代操作
     * @param <T> 命令结果类型
     * @return 命令的完成阶段
     */
    @NotNull
    protected final <T> CompletionStage<T> submit(@NotNull Callable<T> action, @NotNull Callable<T> retiredAction) {
        return this.manager.submit(this, action, retiredAction);
    }

    // 上报 Window 处理器里的异常
    protected final void report(@NotNull String message, @NotNull Throwable throwable) {
        this.manager.report(message, throwable);
    }

    // 当前打开着的类型化菜单处理器
    @Nullable
    protected final M menuHandle() {
        return this.menuHandle;
    }

    // 窗口顶部容器区域的协议槽位数量
    protected final int upperSize() {
        return this.layout.upperSize();
    }

    /**
     * 按显示顺序收集去重后的连接 Inventory 及各自参与点击语义的槽位.
     * 默认只包含经未冻结协议槽展示的 Inventory 槽位: GUI 冻结槽和 Window 虚拟槽位展示的槽位不参与,
     * 未被 GUI 展示的槽位同样不参与, 转移与收集都不会穿透它们.
     * 开启 Inventory 的 includeObscuredSlots 后其未展示槽位也参与, 但 GUI 冻结槽展示的槽位始终不参与.
     *
     * @return 去重后的连接 Inventory 及参与槽位
     */
    private List<ClickSemantics.LinkedInventory> collectLinkedInventories() {
        LinkedHashMap<SparrowInventory, BitSet> visible = new LinkedHashMap<>();
        this.forEachLinkedInventory(this.paths, true, link ->
                visible.computeIfAbsent(link.inventory(), inventory -> new BitSet(inventory.size())).set(link.slot()));
        List<ClickSemantics.LinkedInventory> linked = new ArrayList<>(visible.size());
        visible.forEach((inventory, slots) -> linked.add(new ClickSemantics.LinkedInventory(
                inventory, inventory.includeObscuredSlots() ? this.withObscuredSlots(inventory, slots) : slots)));
        return List.copyOf(linked);
    }

    /**
     * 把未展示槽位扩入参与集. 仅经冻结协议槽展示的槽位仍被排除: 穿透修复不随开关放开.
     *
     * @param inventory 已有可见槽位的连接 Inventory
     * @param visibleSlots 经未冻结协议槽展示的槽位
     * @return 包含未展示槽位的参与集
     */
    private BitSet withObscuredSlots(@NotNull SparrowInventory inventory, @NotNull BitSet visibleSlots) {
        DisplayedSlotPath[] paths = this.paths;
        if (paths == null) {
            return visibleSlots;
        }
        BitSet included = new BitSet(inventory.size());
        included.set(0, inventory.size());
        int protocolSize = this.layout.protocolSize();
        for (int windowSlot = 0; windowSlot < protocolSize; windowSlot++) {
            DisplayedSlotPath path = paths[windowSlot];
            if (path == null || !path.frozen()) {
                continue;
            }
            SlotElement.InventoryLink link = path.inventoryLink();
            if (link != null && link.inventory() == inventory && !visibleSlots.get(link.slot())) {
                included.clear(link.slot());
            }
        }
        return included;
    }

    // 按相反的槽位顺序关闭 DisplayedSlotPath.
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
            } catch (Throwable throwable) {
                failure = ThrowableUtils.combine(failure, throwable);
            }
        }
        return failure;
    }

    /**
     * ClickSemantics 的交互上下文
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
            SlotElement.InventoryLink link = AbstractWindow.this.requirePath(windowSlot).inventoryLink();
            return link == null ? null : new ClickSemantics.LinkedSlot(link.inventory(), link.slot());
        }

        @Override
        public boolean frozenAt(int windowSlot) {
            return AbstractWindow.this.requirePath(windowSlot).frozen();
        }

        // 当场渲染而不是读本地快照: 双击的两个包可能落在同一 tick 里, 那时快照还停在第一个包之前的样子.
        @Override
        public boolean displayedEmptyAt(int windowSlot) {
            DisplayedSlotPath[] paths = AbstractWindow.this.paths;
            if (paths == null || windowSlot < 0 || windowSlot >= paths.length) {
                return true;
            }
            @Nullable DisplayedSlotPath path = paths[windowSlot];
            if (path == null) {
                return true;
            }
            try {
                return path.render().isEmpty();
            } catch (Throwable throwable) {
                // 读不出这一格显示什么就当它有东西: 收集的前提是玩家看到一格空位, 存疑时不放行.
                AbstractWindow.this.manager.report("Failed to render Window slot " + windowSlot, throwable);
                return false;
            }
        }

        @Override
        @Nullable
        public ClickSemantics.LinkedSlot hotbarLink(int hotbarButton) {
            int windowSlot = AbstractWindow.this.layout.windowSlotAtHotbar(hotbarButton);
            DisplayedSlotPath path = AbstractWindow.this.requirePath(windowSlot);
            if (path.frozen()) {
                return null;
            }
            SlotElement.InventoryLink link = path.inventoryLink();
            return link == null ? null : new ClickSemantics.LinkedSlot(link.inventory(), link.slot());
        }

        @Override
        @NotNull
        public List<ClickSemantics.LinkedInventory> linkedInventories() {
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
        public ItemStack offhand() {
            return ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(AbstractWindow.this.viewer.getInventory().getItemInOffHand()));
        }

        @Override
        public void offhand(@Nullable ItemStack item) {
            AbstractWindow.this.viewer.getInventory().setItemInOffHand(item);
        }

        @Override
        public void drop(@NotNull ItemStack item) {
            AbstractWindow.this.viewer.dropItem(item);
        }

        @Override
        public void markDirty(int windowSlot) {
            AbstractWindow.this.notifyUpdate(windowSlot);
        }
    }

    /**
     * 一次交互开始时的 Window 状态快照.
     * 语义引擎在每次派发前后都会调用 {@link #stillValid()}, 子类只负责派发事件本身.
     */
    private abstract class InteractionGuard implements ClickSemantics.InteractionGate {
        private final MenuHandle menu;
        private final long generation;
        private final int stateId;
        private final long pathRevision;
        // 本次交互是否派发 Bukkit 事件, 开始时定下来: 重置事件状态副本, 实际派发和取回事件写入必须按同一个答案走
        private final boolean fireBukkitEvents = SparrowUI.getInstance().fireBukkitInventoryEvents();

        InteractionGuard(MenuHandle menu) {
            this.menu = menu;
            this.generation = AbstractWindow.this.generation;
            this.stateId = menu.stateId();
            this.pathRevision = AbstractWindow.this.interactionPathRevision.get();
        }

        @Override
        public boolean stillValid() {
            return AbstractWindow.this.isInteractionCurrent(this.generation, this.menu, this.stateId, this.pathRevision);
        }

        // 派发 Bukkit 事件前先让 Bukkit 事件状态副本对齐服务端渲染结果; 渲染本身可能跑用户代码, 之后要重新复核.
        boolean refreshEventView() {
            // 不派发 Bukkit 事件就没人读这份副本, 那次渲染纯属白跑
            if (!this.fireBukkitEvents) return true;
            AbstractWindow.this.resetBukkitEventView(this.menu);
            return this.stillValid();
        }

        boolean allowBukkitClick(@NotNull ClickInterpreter.Result.SingleClick click, @NotNull InventoryAction action) {
            return !this.fireBukkitEvents || AbstractWindow.this.manager.bukkitBridge().allowClick(AbstractWindow.this, click, action);
        }

        boolean allowBukkitDrag(@NotNull ClickType clickType, @NotNull ItemStack newCursor, @NotNull Map<Integer, ItemStack> newItems, @NotNull InteractionEdits edits) {
            return !this.fireBukkitEvents || AbstractWindow.this.manager.bukkitBridge().allowDrag(AbstractWindow.this, clickType, newCursor, newItems, edits);
        }

        // 取走事件写进 Bukkit 事件状态副本的光标和槽位并合并进草稿. 这些写入会被下一次 refreshEventView 覆盖, 事件一返回就得取.
        void drainEventEdits(InteractionEdits edits) {
            // 没派发事件就没有本次事件的写入; 副本里残留的是更早的外部写入, 不能算进这次交互
            if (!this.fireBukkitEvents) return;
            ItemStack cursor = this.menu.takeBukkitEventCursor();
            if (cursor != null) {
                edits.cursor(cursor);
            }
            BitSet touched = new BitSet();
            this.menu.drainBukkitEventSlots(touched);
            if (touched.isEmpty()) {
                return;
            }
            InventoryView view = this.menu.view();
            for (int rawSlot = touched.nextSetBit(0); rawSlot >= 0; rawSlot = touched.nextSetBit(rawSlot + 1)) {
                // 写不进事务的槽位(Item 槽, 冻结槽, 或者本次交互没有写集草稿)只能靠全量重发纠正客户端.
                if (!edits.slot(rawSlot, view.getItem(rawSlot))) {
                    AbstractWindow.this.forceFull = true;
                }
            }
        }
    }

    /**
     * 单击的交互闸门: 先派发 Bukkit 点击事件, 再派发被直接连接 Inventory 的点击事件.
     */
    private final class ClickGuard extends InteractionGuard {
        private final ClickInterpreter.Result.SingleClick click;

        ClickGuard(MenuHandle menu, ClickInterpreter.Result.SingleClick click) {
            super(menu);
            this.click = click;
        }

        @Override
        public boolean allowClick(@NotNull InventoryAction action, @NotNull InteractionEdits edits) {
            if (!this.refreshEventView()) {
                return false;
            }
            boolean allowed = this.allowBukkitClick(this.click, action);
            // 取消与否都要把 Bukkit 事件状态副本的写入记录清空, 否则这些写入会被下一个事件误当成自己的.
            // 取消时它们不会被提交: 引擎见到 false 就整体放弃, 攒下的草稿一并作废.
            this.drainEventEdits(edits);
            if (!allowed) {
                AbstractWindow.this.forceFull = true;
                return false;
            }
            return true;
        }

        @Override
        public boolean allowInventoryClick(@NotNull ClickSemantics.LinkedSlot link, @NotNull InventoryAction action, @NotNull InteractionEdits edits) {
            boolean allowed = ClickSemantics.dispatchClickEvent(
                    link.inventory(),
                    link.slot(),
                    AbstractWindow.this.viewer,
                    this.click.clickType(),
                    this.click.hotbarButton(),
                    action,
                    edits
            );
            if (!allowed) {
                AbstractWindow.this.forceFull = true;
            }
            return allowed;
        }
    }

    /**
     * 拖拽的交互闸门: 只派发 Bukkit 拖拽事件, 拖拽候选没有单一事件目标.
     */
    private final class DragGuard extends InteractionGuard {
        private final ClickType clickType;

        DragGuard(MenuHandle menu, ClickType clickType) {
            super(menu);
            this.clickType = clickType;
        }

        @Override
        public boolean allowDrag(@NotNull ItemStack newCursor, @NotNull Map<Integer, ItemStack> newItems, @NotNull InteractionEdits edits) {
            if (!this.refreshEventView()) {
                return false;
            }
            boolean allowed = this.allowBukkitDrag(this.clickType, newCursor, newItems, edits);
            // 监听器也可能绕开事件自己的 setCursor, 直接写 InventoryView; 那份写入后到, 覆盖事件回传值.
            this.drainEventEdits(edits);
            if (!allowed) {
                AbstractWindow.this.forceFull = true;
                return false;
            }
            return true;
        }
    }

    /**
     * 等待客户端确认的窗口状态.
     *
     * @param state 服务器窗口状态
     * @param createdAtMillis 创建时间(毫秒), 用于超时清理
     */
    private record PendingWindowState(int state, long createdAtMillis) {
    }

    /**
     * 客户端本地 Bundle 选择及其对应的显示快照.
     *
     * @param observedBundle 选择发生时该协议槽位(raw slot)显示的 Bundle
     * @param selectedIndex Bundle 内部选择索引
     */
    private record BundleSelectionState(@NotNull ItemStack observedBundle, int selectedIndex) {
    }
}
