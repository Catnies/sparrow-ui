package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
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
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentHolderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsProxy;
import net.momirealms.sparrow.ui.util.*;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 各类 Window 共用的基类, 负责生命周期、槽位路由和协议同步.
 * <p>公开状态用 volatile 快照供跨线程读取; 菜单、显示路径和容器状态只在玩家的实体线程访问.
 * 每次打开都会更新 generation, 迟到的协议输入和异步失效通知会被忽略.
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

    private static final int INCOMING_PER_TICK = 128;               // 每 tick 最多处理的入站输入数, 防止单个玩家占满实体线程
    private static final int CURSOR_AUDIT_INTERVAL = 20;            // 光标复核周期(tick), 定期纠正客户端的光标预测
    private static final long PING_TIMEOUT_MILLIS = 30_000;         // 窗口状态 Ping 的超时时间, 超时未收到 Pong 就丢弃
    private static final BitSet EMPTY_DIRTY_SLOTS = new UnmodifiableBitSet(new BitSet());   // 空的 BitSet 脏位槽.

    // 传入的值和选项
    private final WindowManager manager;
    private final Player viewer;
    private final WindowLayout layout;
    private final Object dirtyLock = new Object();      // 保护脏槽位双缓冲的锁
    private final ClickInterpreter clickInterpreter = new ClickInterpreter();       // 把协议点击包解释成点击或拖拽结果
    private final ClickSemantics.Context semanticsContext = new SemanticsContext(); // 点击语义引擎的槽位路由与玩家侧 IO
    private final Int2ObjectArrayMap<PendingWindowState> pendingWindowStates = new Int2ObjectArrayMap<>(); // 等待 Pong 确认的窗口状态, Ping id -> 待确认状态
    private final BundleSelectionState[] bundleSelections; // 客户端本地 Bundle 选择, 按窗口原始槽隔离
    private final RenderContext cursorRenderContext;    // 光标可视化器的渲染上下文
    private final HandlerList<Runnable> openHandlers;   // 打开处理器
    private final HandlerList<Consumer<InventoryCloseEvent.Reason>> closeHandlers;  // 关闭处理器
    private final HandlerList<Consumer<ClickEvent>> outsideClickHandlers;           // 容器外点击处理器
    private final HandlerList<Consumer<Integer>> windowStateChangeHandlers;         // 窗口状态确认处理器

    // 运行时的状态和缓存
    private volatile Component title;   // 最近一次提交的标题快照
    private volatile Supplier<? extends Component> titleSupplier; // 动态标题来源
    private volatile boolean closeable; // 是否接受客户端主动关闭
    private volatile boolean open;      // Window 是否处于打开状态
    private volatile long generation;   // 当前打开代际, 用来隔离迟到的输入和通知
    private volatile Supplier<? extends @Nullable Window> fallbackWindow; // 玩家主动关闭后的回退的 Window 来源
    private volatile Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer; // 光标显示转换器
    private volatile int serverWindowState; // 最近一次设置的服务器窗口状态
    private volatile int clientWindowState; // 最近一次收到 Pong 确认的客户端窗口状态

    // 仅允许在玩家实体线程访问的状态和缓存
    private @Nullable M menuHandle;                 // 当前菜单句柄, 关闭时为 null
    private @Nullable DisplayedSlotPath[] paths;    // 每个窗口槽位的显示路径
    private @Nullable ItemStack[] localSlots;       // 本地槽位权威快照
    private @Nullable ScheduledTask tickTask;       // 周期 tick 任务
    private @Nullable MenuHandle.CursorSnapshot localCursor; // 最近一次同步的光标快照
    private BitSet dirtySlots;      // 活动脏槽位缓冲, 任意线程的通知都可以写入
    private BitSet spareDirtySlots; // 备用脏槽位缓冲, 与活动缓冲交换复用
    private long windowTick;        // 本次打开以来的 tick 计数
    private boolean cursorDirty;    // 光标是否需要重新核对
    private boolean forceFull;      // 下一次同步是否强制全量
    private boolean menuDirty;      // 菜单是否有槽位内容之外的待同步状态
    private boolean titleDirty;     // 标题是否待重开

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

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateTitle() {
        this.submit(this::notifyUpdateTitle, "Failed to refresh Window title");
    }

    /**
     * 更新本地标题快照; 已打开时标记下一次 tick 重开界面换标题.
     * 连续调用只保留最后一个标题, 避免重复发 OpenScreen 和全量内容包.
     *
     * @param title 新标题
     */
    void notifyUpdateTitle(Component title) {
        this.title = title;
        if (this.open) {
            this.titleDirty = true;
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

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public Component title() {
        return this.title;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCloseable(boolean closeable) {
        this.submit(
                () -> this.closeable = closeable,
                "Failed to update Window closeable state"
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFallbackWindow(@NotNull Supplier<? extends @Nullable Window> fallbackWindow) {
        Objects.requireNonNull(fallbackWindow, "fallbackWindow");
        this.submit(
                () -> this.fallbackWindow = fallbackWindow,
                "Failed to update Window fallback"
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setOpenHandlers(@NotNull List<? extends Runnable> openHandlers) {
        List<Runnable> copy = List.copyOf(openHandlers);
        this.submit(() -> this.openHandlers.set(copy), "Failed to replace Window open handlers");
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public List<Runnable> getOpenHandlers() {
        return this.openHandlers.snapshot();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addOpenHandler(@NotNull Runnable openHandler) {
        this.submit(
                () -> this.openHandlers.append(openHandler),
                "Failed to add Window open handler"
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeOpenHandler(@NotNull Runnable openHandler) {
        this.submit(
                () -> this.openHandlers.remove(openHandler),
                "Failed to remove Window open handler"
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCloseHandlers(@NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> closeHandlers) {
        List<Consumer<InventoryCloseEvent.Reason>> copy = MiscUtils.copyConsumers(closeHandlers);
        this.submit(
                () -> this.closeHandlers.set(copy),
                "Failed to replace Window close handlers"
        );
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public List<Consumer<InventoryCloseEvent.Reason>> getCloseHandlers() {
        return this.closeHandlers.snapshot();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler) {
        Consumer<InventoryCloseEvent.Reason> handler = MiscUtils.narrowConsumer(closeHandler);
        this.submit(
                () -> this.closeHandlers.append(handler),
                "Failed to add Window close handler"
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler) {
        this.submit(
                () -> this.closeHandlers.remove(MiscUtils.narrowConsumer(closeHandler)),
                "Failed to remove Window close handler"
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setOutsideClickHandlers(@NotNull List<? extends Consumer<? super ClickEvent>> outsideClickHandlers) {
        List<Consumer<ClickEvent>> copy = MiscUtils.copyConsumers(outsideClickHandlers);
        this.submit(
                () -> this.outsideClickHandlers.set(copy),
                "Failed to replace Window outside click handlers"
        );
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public List<Consumer<ClickEvent>> getOutsideClickHandlers() {
        return this.outsideClickHandlers.snapshot();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addOutsideClickHandler(@NotNull Consumer<? super ClickEvent> outsideClickHandler) {
        Consumer<ClickEvent> handler = MiscUtils.narrowConsumer(outsideClickHandler);
        this.submit(
                () -> this.outsideClickHandlers.append(handler),
                "Failed to add Window outside click handler"
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeOutsideClickHandler(@NotNull Consumer<? super ClickEvent> outsideClickHandler) {
        this.submit(
                () -> this.outsideClickHandlers.remove(MiscUtils.narrowConsumer(outsideClickHandler)),
                "Failed to remove Window outside click handler"
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setWindowState(int windowState) {
        this.submit(
                () -> this.updateWindowStateOnEntity(windowState),
                "Failed to update Window state"
        );
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public int getServerWindowState() {
        return this.serverWindowState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getClientWindowState() {
        return this.clientWindowState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setWindowStateChangeHandlers(@NotNull List<? extends Consumer<? super Integer>> handlers) {
        List<Consumer<Integer>> copy = MiscUtils.copyConsumers(handlers);
        this.submit(
                () -> this.windowStateChangeHandlers.set(copy),
                "Failed to replace Window state handlers"
        );
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public List<Consumer<Integer>> getWindowStateChangeHandlers() {
        return this.windowStateChangeHandlers.snapshot();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        Consumer<Integer> copied = MiscUtils.narrowConsumer(handler);
        this.submit(
                () -> this.windowStateChangeHandlers.append(copied),
                "Failed to add Window state handler"
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler) {
        this.submit(
                () -> this.windowStateChangeHandlers.remove(MiscUtils.narrowConsumer(handler)),
                "Failed to remove Window state handler"
        );
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> getCursorVisualizer() {
        return this.cursorVisualizer;
    }

    /**
     * {@inheritDoc}
     *
     * <p>超出布局范围的槽位号会被直接忽略.
     */
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
     * {@inheritDoc}
     */
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

    /**
     * 标记菜单有槽位内容之外的待同步状态 (由具体 Window 类型定义).
     */
    protected final void notifyUpdateMenu() {
        this.menuDirty = true;
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public Player viewer() {
        return this.viewer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isOpen() {
        return this.open;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCloseable() {
        return this.closeable;
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public List<Gui> guis() {
        return this.layout.guis();
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public SlotElement.GuiLink guiAt(int windowSlot) {
        return this.layout.guiAt(windowSlot);
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public SlotElement.GuiLink guiAtHotbar(int hotbarSlot) {
        return this.layout.guiAt(this.layout.windowSlotAtHotbar(hotbarSlot));
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public CompletionStage<OpenResult> open() {
        return this.manager.open(this);
    }

    /**
     * 在玩家的实体线程打开 Window: 创建菜单、显示路径和初始协议状态.
     * 所有资源先放在局部变量里, 等初始完整包成功排入 Netty event loop 后才写进字段;
     * 中途失败就按相反方向回滚.
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
        this.clickInterpreter.reset();
        Arrays.fill(this.bundleSelections, null);
        this.pendingWindowStates.clear();
        synchronized (this.dirtyLock) {
            this.dirtySlots.clear();
            this.spareDirtySlots.clear();
        }
        this.refreshTitle();

        // 先在局部变量中构建资源, 避免半初始化状态对 tick 任务可见
        M menuHandle = this.createMenuHandle(this.manager.menuFactory(), generation);
        DisplayedSlotPath[] paths = new DisplayedSlotPath[this.layout.size()];
        ItemStack[] localSlots = new ItemStack[this.layout.size()];
        ScheduledTask tickTask = null;

        try {
            for (int windowSlot = 0; windowSlot < this.layout.size(); windowSlot++) {
                SlotElement.GuiLink link = this.layout.guiAt(windowSlot);
                paths[windowSlot] = new DisplayedSlotPath(this, windowSlot, link.gui(), link.slot());
            }

            // build 可以发生在任意线程; 首帧渲染前在 viewer 实体线程刷新实际连接的镜像库存
            this.refreshLinkedInventories(paths);
            // 构造路径会标记初始 dirty; 全部路径就绪后统一渲染一次, 后续到达的通知留给首个 tick
            this.renderDirtySlots(this.takeDirtySlots(), paths, localSlots);
            this.prepareVirtualContent(menuHandle, localSlots);

            // 安排周期 tick, 再发送初始完整状态, 两者都成功才确认发布菜单已打开的状态
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
     * 为这次打开创建与 Window 类型对应的协议菜单.
     *
     * @param factory 菜单工厂
     * @param generation 本次打开代际
     * @return 尚未打开的菜单底层处理器
     */
    @NotNull
    protected abstract M createMenuHandle(@NotNull MenuFactory factory, long generation);

    /**
     * Window 打开后按列表快照依次运行打开处理器, 单个处理器失败不影响后面的.
     */
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
        // 刷新连接库存和外部真实状态: 镜像型根库存把被引用容器的外部变更收进来变成 External 事件, 快照型库存什么都不做.
        // 因为刷新不是点击语义, 所以冻结槽和虚拟槽连接的库存也要同步.
        this.refreshLinkedInventories(this.paths);
        // 标脏刷新周期到了的显示路径.
        for (int windowSlot = 0; windowSlot < this.paths.length; windowSlot++) {
            DisplayedSlotPath path = this.paths[windowSlot];
            if (path != null && path.refreshPlan().isDue(this.windowTick)) {
                this.notifyUpdate(windowSlot);
            }
        }
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
     * 处理一次已经解释好的单击: 先过 Bukkit 事件, 再复核交互是否被取消, 最后分发.
     * <p>Inventory 槽位交给点击语义引擎走事务; Item 和空槽位按普通 Item 点击分发.
     * 语义动过的槽位已经标脏, 客户端的预测会在同一 tick 的 flush 里被权威状态纠正.
     *
     * @param click 解释好的单击
     * @param menu 当前菜单
     */
    private void handleSingleClick(ClickInterpreter.Result.SingleClick click, MenuHandle menu) {
        long interactionGeneration = this.generation;
        int interactionStateId = menu.stateId();
        // 先过 Bukkit 桥接, 插件可能会拦截这次点击
        InventoryAction action = ClickSemantics.estimateInventoryAction(this.semanticsContext, click.clickType(), click.hotbarButton(), click.rawSlot());
        if (!this.manager.bukkitBridge().allowClick(this, click, action)) {
            return;
        }
        // 桥接的事件处理器可能已经关了或重开了 Window, 复核交互还有效
        if (!this.isInteractionCurrent(interactionGeneration, menu, interactionStateId)) {
            return;
        }
        int rawSlot = click.rawSlot();
        if (rawSlot != InventoryView.OUTSIDE) {
            DisplayedSlotPath path = this.requirePath(rawSlot);
            SlotElement.InventoryLink inventoryLink = path.inventoryLink();
            if (inventoryLink != null && !path.frozen()) {
                InventoryAction currentAction = ClickSemantics.estimateInventoryAction(this.semanticsContext, click.clickType(), click.hotbarButton(), rawSlot);
                boolean allowed = ClickSemantics.dispatchClickEvent(
                        inventoryLink.inventory(),
                        inventoryLink.slot(),
                        this.viewer,
                        click.clickType(),
                        click.hotbarButton(),
                        currentAction
                );
                if (!allowed) {
                    this.forceFull = true;
                    return;
                }
                if (!this.isInteractionCurrent(interactionGeneration, menu, interactionStateId)) {
                    return;
                }
            }
            // Inventory 槽位先给语义引擎; 引擎不接管的(Item/空槽)走普通 Item 分派
            BundleSelectionState bundleSelection = this.bundleSelections[rawSlot];
            if (!ClickSemantics.handleClick(
                    this.semanticsContext,
                    click.clickType(),
                    click.hotbarButton(),
                    rawSlot,
                    bundleSelection == null ? null : bundleSelection.observedBundle(),
                    bundleSelection == null ? -1 : bundleSelection.selectedIndex(),
                    () -> this.bundleSelections[rawSlot] = null
            )) {
                path.handleClick(new ItemClick(click.clickType(), this.viewer, this, rawSlot, click.hotbarButton()));
            }
            return;
        }

        // 容器外点击: 先通知外部处理器, 未取消再交给语义引擎
        ClickEvent event = new ClickEvent(this.viewer, click.clickType(), click.hotbarButton());
        this.outsideClickHandlers.forEachIsolated(
                handler -> handler.accept(event),
                "Failed to handle Window outside click",
                this.manager::report
        );
        if (event.isCancelled() || !this.isInteractionCurrent(interactionGeneration, menu, interactionStateId)) {
            return;
        }
        ClickSemantics.handleOutsideClick(this.semanticsContext, click.clickType());
    }

    /**
     * 处理一次完成的 QUICK_CRAFT 拖拽: 先过 Bukkit 事件, 复核后按拖拽规则分配.
     * <p>参与的 Inventory 槽位构成一个事务, Item 槽位不参与分配.
     *
     * @param drag 解释好的拖拽
     * @param menu 当前菜单
     */
    private void handleDrag(ClickInterpreter.Result.Drag drag, MenuHandle menu) {
        long interactionGeneration = this.generation;
        int interactionStateId = menu.stateId();
        // 先过 Bukkit 桥接
        if (!this.manager.bukkitBridge().allowDrag(this, drag.clickType(), drag.slots())) {
            return;
        }
        // 复核交互仍然有效
        if (!this.isInteractionCurrent(interactionGeneration, menu, interactionStateId)) {
            return;
        }
        ClickSemantics.handleDrag(this.semanticsContext, drag.clickType(), drag.slots());
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
        this.requirePath(packet.slot()).handleBundleSelect(new BundleSelect(this.viewer, packet.selectedIndex()));
    }

    /**
     * 记录客户端在某个原始槽看到的 Bundle 与实际选择.
     * 重复选择同一项、取消选择或越界索引都按原版规则清除.
     *
     * @param rawSlot Bundle 所在的原始槽
     * @param requestedIndex 客户端请求切换到的内部索引
     */
    private void updateBundleSelection(int rawSlot, int requestedIndex) {
        ItemStack[] localSlots = this.localSlots;
        if (localSlots == null) {
            return;
        }
        ItemStack bundle = localSlots[rawSlot];
        if (!ItemUtils.isType(bundle, ItemsProxy.BUNDLE)) {
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
     * todo 只有切石机调用, 这个方法能不能直接收束到具体实现里面?
     * 让某个槽位上当前显示的 Item 直接处理这次点击.
     * <p>这条路不经过 Bukkit 的 InventoryClickEvent;
     * 冻结、背景和空路径仍按 {@link DisplayedSlotPath} 的普通 Item 规则决定是否分发.
     *
     * @param windowSlot 逻辑 Window 槽位
     * @param clickType 点击类型
     */
    protected final void dispatchItemClick(int windowSlot, @NotNull ClickType clickType) {
        this.requirePath(windowSlot).handleClick(new ItemClick(clickType, this.viewer, this, windowSlot, -1));
    }

    /**
     * 返回指定窗口槽位的显示路径.
     *
     * @param windowSlot 窗口槽位
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
     * 确认过完 Bukkit 桥接后这次交互还有效.
     * 事件处理器可能已经把 Window 关了或重开了: generation、菜单实例、state id 任何一个变了,
     * 这次交互就作废.
     *
     * @param interactionGeneration 交互开始时的 generation
     * @param interactionMenu 交互开始时的菜单
     * @param interactionStateId 交互开始时的 state id
     * @return 交互仍然有效时返回 true
     */
    private boolean isInteractionCurrent(long interactionGeneration, MenuHandle interactionMenu, int interactionStateId) {
        return this.open
                && this.generation == interactionGeneration
                && this.menuHandle == interactionMenu
                && interactionMenu.stateId() == interactionStateId;
    }

    /**
     * 对指定路径数组里的连接库存逐一对账, 同一个库存只对账一次.
     *
     * @param paths 显示路径, 为 null 时不做任何事
     */
    private void refreshLinkedInventories(@Nullable DisplayedSlotPath[] paths) {
        LinkedHashSet<Inventory> seen = new LinkedHashSet<>();
        this.forEachLinkedInventory(paths, false, inventory -> {
            if (seen.add(inventory)) {
                inventory.refresh();
            }
        });
    }

    /**
     * 遍历指定路径数组终点连接的库存.
     *
     * @param paths 显示路径, 为 null 时不做任何事
     * @param semanticOnly 是否只遍历参与点击语义的库存(跳过冻结槽与协议外槽位)
     * @param action 对每个库存执行的操作
     */
    private void forEachLinkedInventory(
            @Nullable DisplayedSlotPath[] paths,
            boolean semanticOnly,
            @NotNull Consumer<Inventory> action
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
                action.accept(link.inventory());
            }
        }
    }

    /**
     * 把逻辑尾部(虚拟)区域的渲染结果写进具体菜单的状态.
     * <p>需要在玩家实体线程调用, 初次打开和之后的虚拟槽位或全量刷新都会执行.
     *
     * @param menuHandle 当前菜单句柄
     * @param logicalSlots 包含虚拟尾部的完整逻辑槽位快照
     */
    protected void prepareVirtualContent(@NotNull M menuHandle, ItemStack @NotNull [] logicalSlots) {
    }

    /**
     * 把这一 tick 攒下的脏槽位、光标和标题变化汇总, 交给菜单 Adapter 同步给客户端.
     * 标题变了必须走重开界面加全量内容; 发送失败时保留脏标记, 下个 tick 重试.
     *
     * @param reopenTitle 是否强制以当前标题重开界面
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

        // 虚拟尾部的变化单独投影, 不进协议槽位
        boolean virtualDirty = dirty.nextSetBit(this.layout.protocolSize()) >= 0;
        boolean reopen = reopenTitle || this.titleDirty;
        boolean full = this.forceFull || reopen;
        if (dirty.isEmpty() && !this.cursorDirty && !full && !this.menuDirty) {
            return;
        }

        try {
            // 虚拟槽位或全量刷新时重投虚拟内容
            if (virtualDirty || full) {
                this.prepareVirtualContent(menu, localSlots);
            }
            // 虚拟尾部不属于协议, 发送前从脏集合里清掉
            if (virtualDirty) {
                dirty.clear(this.layout.protocolSize(), dirty.length());
            }
            ItemStack[] protocolSlots = this.protocolSlots(localSlots);
            MenuHandle.CursorSnapshot cursor = this.localCursor;
            if (cursor == null || this.cursorDirty) {
                cursor = this.renderCursor(menu.cursor());
            }
            if (reopen) {
                menu.reopenWithTitle(this.title, protocolSlots, cursor);
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
     * 取出这一批脏槽位, 并立刻清空活动缓冲, 让通知线程可以继续写下一批.
     * 返回的是复用缓冲: 调用方只能读, 不能留着跨 tick 用.
     *
     * @return 本批脏槽位
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
     * 把脏槽位重新渲染进本地快照, 失败时上报并退回上一份本地快照.
     *
     * @param dirty 本批脏槽位
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

    /**
     * 截取协议可见的物理槽位部分.
     * 没有虚拟尾部的布局直接复用原数组, 不做拷贝.
     *
     * @param logicalSlots 完整逻辑槽位
     * @return 协议可见的物理槽位数组
     */
    @NotNull
    private ItemStack[] protocolSlots(ItemStack @NotNull [] logicalSlots) {
        if (logicalSlots.length == this.layout.protocolSize()) {
            return logicalSlots;
        }
        return Arrays.copyOf(logicalSlots, this.layout.protocolSize());
    }

    /**
     * 渲染只给客户端看的光标物品快照.
     * 可视化器出问题就退回显示真实光标, 显示层的扩展不能把容器同步搞坏.
     *
     * @param actualCursor 真实光标
     * @return 光标快照
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
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public CompletionStage<CloseResult> close() {
        return this.manager.close(this);
    }

    /**
     * 在玩家的实体线程关闭已打开的 Window.
     * 先撤掉本地可见状态并停掉输入, 再关菜单、释放显示路径, 最后处理后备 Window 和关闭回调.
     *
     * @param reason 关闭原因
     */
    void closeOnViewerEntity(InventoryCloseEvent.Reason reason) {
        Throwable failure = this.teardownOnEntity(reason);
        // 只有玩家主动关闭才进入 fallback
        if (reason == InventoryCloseEvent.Reason.PLAYER) {
            this.openFallback();
        }
        // 执行关闭处理器
        this.closeHandlers.forEachIsolated(
                handler -> handler.accept(reason),
                "Failed to handle Window close",
                this.manager::report
        );
        ThrowableUtils.throwIfUnchecked(failure);
    }

    /**
     * 撤掉本次打开的本地可见状态, 释放菜单、tick 任务和显示路径.
     * reason 为 null 表示调度退役, 不发客户端关闭包; 否则按该原因走正常菜单关闭.
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
        Arrays.fill(this.bundleSelections, null);

        ScheduledTask previousTickTask = this.tickTask;
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
     * 玩家主动关闭后, 解析并打开后备 Window.
     * 取 Fallback Window 和打开的异常都只上报, 不影响本 Window 的清理.
     */
    private void openFallback() {
        Window fallback;
        try {
            fallback = this.fallbackWindow.get();
        } catch (Throwable throwable) {
            this.manager.report("Failed to resolve Window fallback", throwable);
            return;
        }

        if (fallback != null) {
            fallback.open().exceptionally(throwable -> {
                this.manager.report("Failed to open Window fallback", throwable);
                return null;
            });
        }
    }

    /**
     * 按相反的槽位顺序关闭显示路径, 后续的关闭异常都附加到第一个异常上.
     *
     * @param paths 要关闭的显示路径, 为 null 时直接返回已有失败
     * @param failure 已有的第一个失败, 没有时为 null
     * @return 合并后的第一个失败, 没有任何失败时为 null
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
     * todo: 插件关闭时不触发用户的关闭处理器? 这不合适吧
     * 在调度器注销或插件关闭时回收本地资源.
     * 这条路不发客户端关闭包, 也不触发后备 Window 和用户的关闭处理器.
     *
     * @return 退役前是否处于打开状态
     */
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
     * @return 菜单视图
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

    /**
     * 上报 Window 处理器里的异常.
     *
     * @param message 错误说明
     * @param throwable 异常
     */
    protected final void report(@NotNull String message, @NotNull Throwable throwable) {
        this.manager.report(message, throwable);
    }

    /**
     * 返回当前打开着的类型化菜单处理器, Window 关闭时返回 null.
     *
     * @return 当前菜单处理器, 未打开时为 null
     */
    @Nullable
    protected final M menuHandle() {
        return this.menuHandle;
    }

    /**
     * 返回窗口顶部容器区域的协议槽位数量.
     *
     * @return 顶部槽位数量
     */
    protected final int upperSize() {
        return this.layout.upperSize();
    }

    /**
     * 按显示顺序收集去重后的连接库存, 作为点击语义的目标域.
     * 冻结槽和协议外(虚拟尾部)槽位连接的库存不算在内, 冻结的展示库存不能被转移或收集穿透.
     *
     * @return 去重后的连接库存列表
     */
    private List<Inventory> collectLinkedInventories() {
        LinkedHashSet<Inventory> inventories = new LinkedHashSet<>();
        this.forEachLinkedInventory(this.paths, true, inventories::add);
        return List.copyOf(inventories);
    }

    /**
     * 点击语义引擎的交互上下文: 显示路径、光标与玩家侧库存 IO.
     * 全部方法只在玩家实体线程被点击处理路径调用.
     */
    private final class SemanticsContext implements ClickSemantics.Context {

        /**
         * {@inheritDoc}
         */
        @Override
        @NotNull
        public Player viewer() {
            return AbstractWindow.this.viewer;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @Nullable
        public ClickSemantics.LinkedSlot linkAt(int windowSlot) {
            SlotElement.InventoryLink link = AbstractWindow.this.requirePath(windowSlot).inventoryLink();
            return link == null ? null : new ClickSemantics.LinkedSlot(link.inventory(), link.slot());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean frozenAt(int windowSlot) {
            return AbstractWindow.this.requirePath(windowSlot).frozen();
        }

        /**
         * {@inheritDoc}
         */
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

        /**
         * {@inheritDoc}
         */
        @Override
        @NotNull
        public List<Inventory> linkedInventories() {
            return AbstractWindow.this.collectLinkedInventories();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NotNull
        public ItemStack cursor() {
            M menu = AbstractWindow.this.menuHandle;
            return menu != null ? menu.cursor() : ItemStack.empty();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void cursor(@NotNull ItemStack cursor) {
            M menu = AbstractWindow.this.menuHandle;
            if (menu != null) {
                menu.cursor(cursor);
            }
            AbstractWindow.this.cursorDirty = true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @Nullable
        public ItemStack offhand() {
            return ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(AbstractWindow.this.viewer.getInventory().getItemInOffHand()));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void offhand(@Nullable ItemStack item) {
            AbstractWindow.this.viewer.getInventory().setItemInOffHand(item);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void drop(@NotNull ItemStack item) {
            // 走玩家丢弃路径: 触发 PlayerDropItemEvent, 携带投掷归属与原版轨迹
            AbstractWindow.this.viewer.dropItem(item);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void markDirty(int windowSlot) {
            AbstractWindow.this.notifyUpdate(windowSlot);
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
     * @param observedBundle 选择发生时该原始槽显示的 Bundle
     * @param selectedIndex Bundle 内部选择索引
     */
    private record BundleSelectionState(@NotNull ItemStack observedBundle, int selectedIndex) {
    }
}
