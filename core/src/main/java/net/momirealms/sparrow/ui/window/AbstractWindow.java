package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.exception.ViewerUnavailableException;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuHandle;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.inventory.InventorySequence;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.click.ClickSemantics;
import net.momirealms.sparrow.ui.inventory.click.InteractionEdits;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentHolderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.component.BundleContentsProxy;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import net.momirealms.sparrow.ui.util.UnmodifiableBitSet;
import net.momirealms.sparrow.ui.visual.CursorVisual;
import net.momirealms.sparrow.ui.visual.CursorVisualImpl;
import net.momirealms.sparrow.ui.visual.ResolvedVisual;
import net.momirealms.sparrow.ui.visual.VisualLayer;
import net.momirealms.sparrow.ui.visual.WindowVisual;
import net.momirealms.sparrow.ui.visual.WindowVisualImpl;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import net.momirealms.sparrow.ui.visual.animation.TitleAnimationDefinition;
import net.momirealms.sparrow.ui.window.click.WindowOutsideClick;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

abstract class AbstractWindow<M extends MenuHandle> implements Window {
    /**
     * Builder 交给共享生命周期构造器的不可变设置快照.
     *
     * @param titleSupplier 动态标题来源
     * @param closeable 是否接受客户端主动关闭
     * @param openHandlers 打开处理器
     * @param closeHandlers 关闭处理器
     * @param outsideClickHandlers 容器外点击处理器
     * @param backOnPlayerClose 玩家主动关闭时是否返回来源窗口
     * @param data 随 Window 携带的用户对象
     * @param rootSessionKind 本窗成为链根时新会话的类型
     * @param rootSessionEndHandlers 本窗成为链根时装进新会话的结束处理器
     * @param windowState 初始服务器 Window 状态
     * @param windowStateChangeHandlers 客户端状态确认处理器
     * @param windowVisualLayer Window 槽位视觉配置的初始全局层
     * @param cursorVisualLayer 光标视觉配置的初始层
     */
    record Settings(
            @NotNull Supplier<? extends Component> titleSupplier,
            boolean closeable,
            @NotNull List<Runnable> openHandlers,
            @NotNull List<Consumer<InventoryCloseEvent.Reason>> closeHandlers,
            @NotNull List<Consumer<WindowOutsideClick>> outsideClickHandlers,
            boolean backOnPlayerClose,
            @Nullable Object data,
            @NotNull WindowSession.Kind rootSessionKind,
            @NotNull List<Consumer<InventoryCloseEvent.Reason>> rootSessionEndHandlers,
            int windowState,
            @NotNull List<Consumer<Integer>> windowStateChangeHandlers,
            @NotNull VisualLayer windowVisualLayer,
            @NotNull VisualLayer cursorVisualLayer
    ) {
    }

    // 系统常量
    private static final int INCOMING_PER_TICK = 128;               // 每 tick 最多处理的入站输入数, 防止单个玩家占满实体线程
    private static final int CURSOR_AUDIT_INTERVAL = 20;            // 光标复核周期(tick), 定期纠正客户端的光标预测
    private static final long PING_TIMEOUT_MILLIS = 30_000;         // 窗口状态 Ping 的超时时间, 超时未收到 Pong 就丢弃
    private static final int STATE_ID_RING = 32768;                 // 原版 state id 取值范围
    private static final BitSet EMPTY_DIRTY_SLOTS = new UnmodifiableBitSet(new BitSet());   // 空的 BitSet 脏位槽

    // 身份与固定配置, 构造后不变
    private final WindowManager manager;
    private final Player viewer;
    private final WindowLayout layout;
    private final @Nullable Object data;                // 随窗携带的用户对象
    private final WindowSession.Kind rootSessionKind;       // 成为链根时新会话的类型
    private final List<Consumer<InventoryCloseEvent.Reason>> rootSessionEndHandlers; // 成为链根时装进新会话的结束处理器
    private final Bindings bindings = Bindings.suspended(); // 本 Window 持有的 Signal 绑定, 出生即挂起, 首次打开才挂上

    // 用户处理器
    private final HandlerList<Runnable> openHandlers;   // 打开处理器
    private final HandlerList<Consumer<InventoryCloseEvent.Reason>> closeHandlers;  // 关闭处理器
    private final HandlerList<Consumer<WindowOutsideClick>> outsideClickHandlers;   // 容器外点击处理器
    private final HandlerList<Consumer<Integer>> windowStateChangeHandlers;         // 窗口状态确认处理器

    // 生命周期
    private volatile boolean open;      // Window 是否处于打开状态
    private volatile long generation;   // 当前打开代际, 用来隔离迟到的输入和通知
    private volatile @Nullable AbstractWindowSession session; // 所属会话, 不在任何链上时为 null
    private @Nullable M menuHandle;     // 当前菜单句柄, 关闭时为 null; 仅玩家实体线程访问
    private @Nullable ScheduledTask tickTask; // 周期 tick 任务; 仅玩家实体线程访问
    private long windowTick;            // 本次打开以来的 tick 计数; 仅玩家实体线程访问

    // 行为开关
    private volatile boolean closeable; // 是否接受客户端主动关闭
    private volatile boolean backOnPlayerClose; // 玩家主动关闭时所在会话是否返回来源窗口
    private volatile boolean offhandFrozen; // 是否阻止玩家经此 Window 交换副手

    // 标题
    private volatile Component title;   // 最近一次已应用的标题快照
    private volatile Supplier<? extends Component> titleSupplier; // 动态标题来源
    private @Nullable Component sentTitle; // 最近一次成功进入发送流程的标题; 仅玩家实体线程访问
    private boolean titleDirty;         // 标题是否待重开; 仅玩家实体线程访问

    // 标题动画
    private final Object titleAnimationLock = new Object(); // 只保护标题动画通道数组替换, 失效投递一律出了锁再做
    private volatile ActiveTitleAnimation[] titleAnimations = ActiveTitleAnimation.NONE; // 播放中的标题动画, 按开始序排列, 求值时从末尾往前看
    private AnimationHandle.FinishReason titleAnimationsFinishing; // 通道正在以这个原因整体终结, 非 null 期间新播放不入场; 由 titleAnimationLock 保护

    // 槽位渲染与同步
    private final Object dirtyLock = new Object();      // 保护脏槽位双缓冲的锁
    private BitSet dirtySlots;      // 活动脏槽位缓冲, 任意线程的通知都可以写入
    private BitSet spareDirtySlots; // 备用脏槽位缓冲, 与活动缓冲交换复用
    private final BitSet renderedBeforeEvent = new BitSet(); // 本 tick 已在 Bukkit 事件前渲染, 仍待最终同步的槽位
    private @Nullable DisplayedSlotPath[] paths;    // 每个 Window 槽位的显示路径
    private @Nullable ItemStack[] localSlots;       // 最近一次渲染的 Window 槽位内容
    private boolean forceFull;      // 下一次同步是否强制全量
    private boolean menuDirty;      // 菜单是否有槽位内容之外的待同步状态
    private boolean forceReopen;    // 即使标题相同也必须重开菜单

    // 槽位视觉
    private final WindowVisualImpl windowVisual;        // Window 槽位视觉配置与逐槽显示路径失效订阅

    // 光标
    private boolean cursorDirty;    // 光标是否需要重新核对
    private final CursorVisualImpl cursorVisual;        // 光标视觉配置
    private final RenderContext cursorRenderContext;    // 光标视觉映射的渲染上下文
    private final RenderCell cursorRenderCell;          // 光标异步视觉的投影, 跨打开代际经 reset 复用
    private final AtomicBoolean cursorCompletionPending = new AtomicBoolean(); // 光标异步视觉的完成通知, 不等同于光标本身变化
    private @Nullable MenuHandle.CursorSnapshot localCursor; // 最近一次同步的光标快照; 仅玩家实体线程访问

    // tick 任务刷新目标
    private @Nullable List<SparrowInventory> refreshInventories; // 每 tick 要刷新的 Inventory, null 表示要重新收集
    private @Nullable Pane[] refreshPanes;          // 收集刷新目标时路径上出现过的 Pane, 已去重
    private @Nullable Object[] refreshDeclarations; // 与 refreshPanes 同下标, 收集当时各自的 participatingSequences(), 只比较引用
    private @Nullable InventorySequence[] refreshSequences; // 收集刷新目标时路径上出现过的序列, 已去重
    private @Nullable Object[] refreshSequenceMembers;      // 与 refreshSequences 同下标, 收集当时各自的成员名单, 只比较引用

    // 点击与交互
    private final ClickInterpreter clickInterpreter = new ClickInterpreter();       // 把协议点击包解释成点击或拖拽结果
    private final ClickSemantics.Context semanticsContext = new SemanticsContext(); // 点击语义引擎的目标解析与玩家侧 IO
    private final AtomicLong interactionPathRevision = new AtomicLong(); // InventoryLink 终点或冻结语义的版本
    private final BundleSelectionState[] bundleSelections; // 客户端本地 Bundle 选择, 按协议槽位(raw slot)隔离
    private final boolean[] frozenSlots; // Window 侧的单槽冻结, 覆盖整个路径数组域, 与路径沿途的 Pane 冻结按或合成
    private final int[] structureBarriers; // 每个协议槽位的交互结构屏障, 客户端 state id 早于它的交互按过时丢弃
    private final BitSet pendingStructureSlots = new BitSet(); // 结构已变但还没同步给客户端的协议槽位

    // 窗口状态与 Ping/Pong 确认
    private final Int2ObjectArrayMap<PendingWindowState> pendingWindowStates = new Int2ObjectArrayMap<>(); // 等待 Pong 确认的窗口状态, Ping id -> 待确认状态
    private volatile int serverWindowState; // 最近一次设置的服务器窗口状态
    private volatile int clientWindowState; // 最近一次收到 Pong 确认的客户端窗口状态

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
        this.structureBarriers = new int[layout.protocolSize()];
        this.title = Component.empty();
        this.titleSupplier = settings.titleSupplier();
        this.closeable = settings.closeable();
        this.openHandlers = new HandlerList<>(settings.openHandlers());
        this.closeHandlers = new HandlerList<>(settings.closeHandlers());
        this.outsideClickHandlers = new HandlerList<>(settings.outsideClickHandlers());
        this.backOnPlayerClose = settings.backOnPlayerClose();
        this.data = settings.data();
        this.rootSessionKind = settings.rootSessionKind();
        this.rootSessionEndHandlers = settings.rootSessionEndHandlers();
        this.serverWindowState = settings.windowState();
        this.windowStateChangeHandlers = new HandlerList<>(settings.windowStateChangeHandlers());
        this.dirtySlots = new BitSet(layout.size());
        this.spareDirtySlots = new BitSet(layout.size());
        this.windowVisual = new WindowVisualImpl(this.bindings, layout.size());
        // 此刻还没有任何显示路径附着, 写入 Builder 的初始全局层不会通知到任何人
        VisualLayer windowVisualLayer = settings.windowVisualLayer();
        if (windowVisualLayer != VisualLayer.NONE) {
            this.windowVisual.setVisualizerProvider(windowVisualLayer.visualizer(), windowVisualLayer.placeholder());
        }
        this.cursorRenderContext = RenderContext.cursor(this);
        this.cursorVisual = new CursorVisualImpl(this.bindings, settings.cursorVisualLayer());
        this.cursorRenderCell = new RenderCell(
                this.cursorRenderContext,
                () -> this.cursorCompletionPending.set(true),
                throwable -> SparrowUI.getInstance().handleException("Failed to render asynchronous Window cursor", throwable)
        );
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
     * 更新本地配置标题快照.
     *
     * @param title 新标题
     */
    void notifyUpdateTitle(Component title) {
        this.title = title;
        this.recomputeTitleDirty();
    }

    // 按有效标题重算是否欠客户端一次重开, 配置标题写入与标题动画的帧推进、摘层共用这一条判定.
    private void recomputeTitleDirty() {
        if (this.open) {
            this.titleDirty = !Objects.equals(this.sentTitle, this.effectiveTitle());
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

    @NotNull
    @Override
    public AnimationHandle playTitleAnimation(@NotNull TitleAnimationDefinition animationDefinition) {
        // 先解析时钟, 把非法周期挡在入场之前
        long periodTicks = animationDefinition.periodTicks();
        Signal<Long> clock = Signals.everyTicks(periodTicks);
        // 起播时刻对齐到本周期的共享节拍, 与槽位动画同理, 不对齐的话首帧会被拉长最多一个周期
        long startTick = Signals.ticking().get() / periodTicks * periodTicks;
        ActiveTitleAnimation playing = new ActiveTitleAnimation(this, animationDefinition, startTick);
        AnimationHandle.FinishReason finishing;
        synchronized (this.titleAnimationLock) {
            finishing = this.titleAnimationsFinishing;
            if (finishing == null) {
                ActiveTitleAnimation[] current = this.titleAnimations;
                ActiveTitleAnimation[] animations = Arrays.copyOf(current, current.length + 1);
                animations[current.length] = playing;
                this.titleAnimations = animations;
            }
        }
        // 通道正在整体终结时不再放新播放进场, 当场以同一原因结束, 句柄的结束回调照常恰好触发一次
        if (finishing != null) {
            playing.finish(finishing);
            return playing;
        }
        // 入场即盖住配置标题
        this.notifyTitleAnimationChanged();
        try {
            playing.startClock(clock);
        } catch (RuntimeException exception) {
            // 挂钟失败的这一次播放既不会推进也没有句柄能取消它, 摘掉它再把失败交出去
            this.removeTitleAnimation(playing);
            throw exception;
        }
        return playing;
    }

    // 标题动画的帧推进与摘层共用的失效投递: 排到实体线程重算标题是否待重开.
    // titleDirty 仅实体线程访问, 时钟回调只许经这里投递, 不得直写.
    void notifyTitleAnimationChanged() {
        this.submit(this::recomputeTitleDirty, "Failed to update Window title animation");
    }

    // 有效标题为标题动画通道自新向旧第一个非 null 帧, 全放行则为配置标题.
    private Component effectiveTitle() {
        ActiveTitleAnimation[] animations = this.titleAnimations;
        if (animations.length > 0) {
            long nowTick = Signals.ticking().get();
            for (int index = animations.length - 1; index >= 0; index--) {
                Component frame;
                try {
                    frame = animations[index].frameAt(nowTick);
                } catch (RuntimeException exception) {
                    this.report("Failed to evaluate Window title animation frame", exception);
                    continue;
                }
                if (frame != null) {
                    return frame;
                }
            }
        }
        return this.title;
    }

    // 以给定原因结束全部在播标题动画, 返回时通道一定是空的.
    // 终结期间入场的播放当场以同一原因结束而不进通道, 否则结束回调里的链式续播会让通道死灰复燃.
    // 某个结束回调抛异常也照样终结剩下的, 攒起来交给调用方抛.
    private void finishTitleAnimations(@NotNull AnimationHandle.FinishReason reason) {
        ActiveTitleAnimation[] animations;
        synchronized (this.titleAnimationLock) {
            this.titleAnimationsFinishing = reason;
            animations = this.titleAnimations;
            this.titleAnimations = ActiveTitleAnimation.NONE;
        }
        RuntimeException failure = null;
        try {
            for (int index = 0; index < animations.length; index++) {
                try {
                    animations[index].finish(reason);
                } catch (RuntimeException exception) {
                    failure = ThrowableUtils.combine(failure, exception);
                }
            }
        } finally {
            synchronized (this.titleAnimationLock) {
                this.titleAnimationsFinishing = null;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // 摘除一次标题播放并让配置标题或更早的播放在下一次同步时露出, 已经不在场时静默返回.
    void removeTitleAnimation(@NotNull ActiveTitleAnimation animation) {
        synchronized (this.titleAnimationLock) {
            ActiveTitleAnimation[] current = this.titleAnimations;
            int index = indexOf(current, animation);
            if (index < 0) return;
            ActiveTitleAnimation[] animations;
            if (current.length == 1) {
                animations = ActiveTitleAnimation.NONE;
            } else {
                animations = new ActiveTitleAnimation[current.length - 1];
                System.arraycopy(current, 0, animations, 0, index);
                System.arraycopy(current, index + 1, animations, index, current.length - index - 1);
            }
            this.titleAnimations = animations;
        }
        this.notifyTitleAnimationChanged();
    }

    private static int indexOf(ActiveTitleAnimation @NotNull [] animations, @NotNull ActiveTitleAnimation animation) {
        for (int index = 0; index < animations.length; index++) {
            if (animations[index] == animation) {
                return index;
            }
        }
        return -1;
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
    public boolean backOnPlayerClose() {
        return this.backOnPlayerClose;
    }

    @Override
    public void backOnPlayerClose(boolean backOnPlayerClose) {
        this.submit(
                () -> this.backOnPlayerClose = backOnPlayerClose,
                "Failed to update Window back-on-player-close state"
        );
    }

    @Nullable
    @Override
    public WindowSession session() {
        return this.session;
    }

    // 所属会话的实现视图, 供库内判定链内交接与关闭去向.
    @Nullable
    AbstractWindowSession sessionImpl() {
        return this.session;
    }

    // 更新会话归属, 由会话在玩家实体线程写入.
    void session(@Nullable AbstractWindowSession session) {
        this.session = session;
    }

    @Nullable
    @Override
    public Object data() {
        return this.data;
    }

    // 本窗成为链根时新会话的类型.
    @NotNull
    WindowSession.Kind rootSessionKind() {
        return this.rootSessionKind;
    }

    // 本窗成为链根时装进新会话的结束处理器.
    @NotNull
    List<Consumer<InventoryCloseEvent.Reason>> rootSessionEndHandlers() {
        return this.rootSessionEndHandlers;
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
        return this.bindings.bind(() -> signal.onDirty(() -> callback.accept(this)));
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
    @NotNull
    public WindowVisual visual() {
        return this.windowVisual;
    }

    @Override
    public CursorVisual cursorVisual() {
        return this.cursorVisual;
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizerProvider() {
        return this.cursorVisual.visualizerProvider();
    }

    @Override
    public void setCursorVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizerProvider) {
        this.cursorVisual.setVisualizerProvider(cursorVisualizerProvider);
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
    public Pane lowerPane() {
        return this.layout.lowerPane();
    }

    @NotNull
    @Override
    public List<Pane> panes() {
        return this.layout.panes();
    }

    @NotNull
    @Override
    public Element.PaneLink paneAt(int windowSlot) {
        return this.layout.paneAt(windowSlot);
    }

    @NotNull
    @Override
    public Element.PaneLink paneAtHotbar(int hotbarSlot) {
        return this.layout.paneAt(this.layout.windowSlotAtHotbar(hotbarSlot));
    }

    @Override
    public int windowSlotAtHotbar(int hotbarSlot) {
        return this.layout.windowSlotAtHotbar(hotbarSlot);
    }

    @NotNull
    @Override
    public CompletableFuture<OpenResult> open() {
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
        this.cursorRenderCell.reset();
        this.cursorCompletionPending.set(false);
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
            // 绑定跟随打开期: 上一次关闭时摘掉的声明在这里重新挂上, 首帧渲染前必须就位
            this.bindings.resumeAll();
            for (int windowSlot = 0; windowSlot < this.layout.size(); windowSlot++) {
                Element.PaneLink link = this.layout.paneAt(windowSlot);
                paths[windowSlot] = new DisplayedSlotPath(this, windowSlot, link.pane(), link.slot());
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
            this.cursorVisual.takeDirty();
            MenuHandle.CursorSnapshot localCursor = this.renderCursor(menuHandle.cursor());
            Component title = this.effectiveTitle();
            menuHandle.open(title, this.protocolSlots(localSlots), localCursor);

            this.menuHandle = menuHandle;
            this.paths = paths;
            this.localSlots = localSlots;
            this.localCursor = localCursor;
            this.sentTitle = title;
            this.tickTask = tickTask;
            Arrays.fill(this.structureBarriers, menuHandle.stateId());
            this.pendingStructureSlots.clear();
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
        this.openHandlers.forEachIsolated(Runnable::run, "Failed to handle Window open", SparrowUI.getInstance()::handleException);
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
            SparrowUI.getInstance().handleException(
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
                SparrowUI.getInstance().handleException("Failed to process a Window interaction", throwable);
            }
            if (!this.open) {
                return;
            }
        }

        // 输入稳定后再汇总所有本 tick 的失效并发送一次同步
        this.windowTick++;
        // 刷新连接的 Inventory, ReferencingInventory 把 Bukkit 容器变更同步进镜像并生成 External 事件, 其他 Inventory 不处理.
        // 因为刷新不是点击语义, 所以 Pane 冻结槽和 Window 虚拟槽位连接的 Inventory 也要同步.
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
     * 校验交互是否属于当前容器状态, 解释成点击或拖拽后分发给 Pane 或容器外处理器.
     * 不可信的输入会强制全量恢复; 正常的输入只复核客户端预测碰过的槽位.
     *
     * @param interaction 待处理的交互
     */
    private void handleInteraction(MenuInput.Common.Interaction interaction) {
        M menu = this.menuHandle;
        // 不属于当前状态的输入就重置解释器, 强制全量同步
        if (menu == null || this.isStaleAcrossStructureChange(interaction) || !menu.accepts(interaction)) {
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
                SparrowUI.getInstance()::handleException
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
        ItemDragClick.Stop[] stops = new ItemDragClick.Stop[windowSlots.size()];
        for (int index = 0; index < stops.length; index++) {
            int windowSlot = windowSlots.get(index);
            stops[index] = new ItemDragClick.Stop(windowSlot, this.requirePath(windowSlot).kind());
        }
        // 路径先定型成不可变列表, 逐站构造 ItemDragClick 时 List.copyOf 原样返回它, 不再每个 Item 复制一遍整条路径
        List<ItemDragClick.Stop> path = List.of(stops);
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
     * 选择只转发给 Pane 槽位, 不同步客户端已经维护的本地选择状态.
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
                || previous != null && previous.selectedIndex() == requestedIndex && ItemUtils.isContentEqual(previous.observedBundle(), bundle)) {
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
                SparrowUI.getInstance()::handleException
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
     * Pane 冻结, 背景和空路径仍按 {@link DisplayedSlotPath} 的普通 Item 规则决定是否分发.
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
            throw new IllegalStateException("window slot has no displayed Pane path: " + windowSlot);
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
        // 强制处理每条路径攒下的 Pane 结构变化, 让下面的复核看到最新的交互终点与冻结语义.
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

    /**
     * 记下一个协议槽位的交互结构变了 (叶 Element 变化, InventoryLink 或 Frozen 操作).
     * <p>这意味着这个槽位如果还有客户端已发出但服务端未收到的包, 一律按过时丢弃.
     *
     * @param windowSlot 结构发生变化的 Window 槽位
     */
    void notifyInteractionStructureChanged(int windowSlot) {
        if (windowSlot >= 0 && windowSlot < this.structureBarriers.length) {
            this.pendingStructureSlots.set(windowSlot);
        }
    }

    // 结构变化随本批同步发了出去, 之后到达的交互才算见过新结构.
    private void commitStructureBarriers(MenuHandle menu) {
        if (this.pendingStructureSlots.isEmpty()) return;
        int stateId = menu.stateId();
        for (
                int windowSlot = this.pendingStructureSlots.nextSetBit(0);
                windowSlot >= 0;
                windowSlot = this.pendingStructureSlots.nextSetBit(windowSlot + 1)
        ) {
            this.structureBarriers[windowSlot] = stateId;
        }
        this.pendingStructureSlots.clear();
    }

    /**
     * 判断一次交互是不是在客户端还没同步最新的元素/状态时点出来的.
     * <p>客户端视图落后一拍本身不算问题 (原版同样照常处理), 但是如果 "点的那一格在客户端不知情时换过元素/状态"
     * 才说明这次点击的意图已经落空, 比如玩家看到的还是旧按钮, 但是服务端已经记为新的按钮.
     *
     * @param interaction 待判定的交互
     * @return 交互已经跨过结构变化时返回 true
     */
    private boolean isStaleAcrossStructureChange(MenuInput.Common.Interaction interaction) {
        // 槽号直接来自数据包, 容器外点击是 -999.
        int windowSlot = interaction.slot();
        if (windowSlot < 0 || windowSlot >= this.structureBarriers.length) return false;
        // 结构刚变还没发出去, 客户端手上一定是旧结构
        if (this.pendingStructureSlots.get(windowSlot)) return true;
        // state id 是环形计数, 只能按环上的距离判断新旧, 落在后半环即客户端还没见过屏障那一版
        return ((interaction.stateId() - this.structureBarriers[windowSlot]) & (STATE_ID_RING - 1)) >= STATE_ID_RING / 2;
    }

    // DisplayedSlotPath 在重新解析后发现 InventoryLink 终点或冻结语义改变时调用.
    void notifyInteractionPathChanged() {
        this.interactionPathRevision.incrementAndGet();
    }

    /**
     * 对指定路径数组里的连接Inventory逐一刷新, 同一个Inventory只刷新一次.
     * <p>这个方法每 tick 都跑, 但要刷新的其实就那么一两个 Inventory, 而且几乎从不变化.
     * 所以名单收集一次就存下来, 只在显示路径重建过, 或者哪个 Pane 改了声明时才重新收集.
     *
     * @param paths 显示路径, 为 null 时不做任何事
     */
    private void refreshLinkedInventories(@Nullable DisplayedSlotPath[] paths) {
        if (this.refreshInventories == null || this.declarationsChanged()) {
            this.collectRefreshTargets(paths);
        }
        List<SparrowInventory> targets = this.refreshInventories;
        assert targets != null;
        for (int index = 0; index < targets.size(); index++) {
            targets.get(index).refresh();
        }
    }

    /**
     * 重新收集每 tick 要刷新的 Inventory, 同时记下这次依据了哪些 Pane 以及它们当时的声明.
     *
     * @param paths 显示路径, 为 null 时收集结果为空
     */
    private void collectRefreshTargets(@Nullable DisplayedSlotPath[] paths) {
        LinkedHashSet<SparrowInventory> targets = new LinkedHashSet<>();
        this.forEachLinkedInventory(paths, false, link -> targets.add(link.inventory()));

        ArrayList<Pane> panes = new ArrayList<>();
        ArrayList<Object> declarations = new ArrayList<>();
        LinkedHashSet<InventorySequence> sequences = new LinkedHashSet<>();
        this.forEachPathPane(paths, pane -> {
            // 一个 Pane 通常铺满一大片槽位, 每个槽位都会走到这里, 只记第一次
            if (panes.contains(pane)) return;
            panes.add(pane);
            Set<InventorySequence> declared = pane.participatingSequences();
            declarations.add(declared);
            sequences.addAll(declared);
        });

        // Pane 声明关联的 Inventory 一个槽位都没被展示, 但它同样参与点击, 外部变更也要吸收;
        ArrayList<Object> sequenceMembers = new ArrayList<>(sequences.size());
        for (InventorySequence sequence : sequences) {
            List<SparrowInventory> members = sequence.inventories();
            sequenceMembers.add(members);
            targets.addAll(members);
        }

        this.refreshInventories = List.copyOf(targets);
        this.refreshPanes = panes.toArray(new Pane[0]);
        this.refreshDeclarations = declarations.toArray();
        this.refreshSequences = sequences.toArray(new InventorySequence[0]);
        this.refreshSequenceMembers = sequenceMembers.toArray();
    }

    /**
     * 检查上次收集之后有没有哪个 Pane 改过声明, 或者哪个序列换过成员.
     * <p>Pane 用写时复制保存声明, 序列每次增减成员也换一份新名单, 所以两边都比引用就够,不必逐个比内容.
     *
     * @return 需要重新收集时返回 true
     */
    private boolean declarationsChanged() {
        Pane[] panes = this.refreshPanes;
        if (panes == null) return true;
        for (int index = 0; index < panes.length; index++) {
            if (panes[index].participatingSequences() != this.refreshDeclarations[index]) {
                return true;
            }
        }
        InventorySequence[] sequences = this.refreshSequences;
        assert sequences != null;
        for (int index = 0; index < sequences.length; index++) {
            // 顺带把已经退役的成员剔出去, 名单一换这里就会发现
            if (sequences[index].inventories() != this.refreshSequenceMembers[index]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 丢弃存下来的刷新名单.
     * <p>显示路径重建之后, 路径终点连接的 Inventory 和路径经过的 Pane 都可能换了.
     */
    void invalidateRefreshTargets() {
        this.refreshInventories = null;
    }

    /**
     * 遍历指定路径数组终点的 InventoryLink.
     *
     * @param paths 显示路径, 为 null 时不做任何事
     * @param semanticOnly 是否只遍历参与点击的连接(跳过 Pane 冻结槽与 Window 虚拟槽位)
     * @param action 对每个终点连接执行的操作
     */
    private void forEachLinkedInventory(
            @Nullable DisplayedSlotPath[] paths,
            boolean semanticOnly,
            @NotNull Consumer<Element.InventoryLink> action
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
            Element.InventoryLink link = path.inventoryLink();
            if (link != null) {
                action.accept(link);
            }
        }
    }

    /**
     * 遍历显示路径经过的每一层 Pane 所声明的额外参与 Inventory.
     * <p>同一个 Inventory 可能被多个 Pane 声明, 因此会重复交给 {@code action}, 由调用方去重.
     *
     * @param paths 显示路径, 为 null 时不做任何事
     * @param action 对每个声明的 Inventory 执行的操作
     */
    private void forEachPaneLinkedInventory(
            @Nullable DisplayedSlotPath[] paths,
            @NotNull Consumer<SparrowInventory> action
    ) {
        this.forEachPathPane(paths, pane -> {
            // 绝大多数 Pane 一个都没声明, 这里直接跳过, 序列的成员随时会变, 每次规划都现取一份, 不缓存.
            for (InventorySequence sequence : pane.participatingSequences()) {
                List<SparrowInventory> members = sequence.inventories();
                for (int index = 0; index < members.size(); index++) {
                    action.accept(members.get(index));
                }
            }
        });
    }

    /**
     * 遍历显示路径经过的每一层 Pane. 同一个 Pane 出现在多条路径上时会重复交给 {@code action}.
     *
     * @param paths 显示路径, 为 null 时不做任何事
     * @param action 对每一层 Pane 执行的操作
     */
    private void forEachPathPane(@Nullable DisplayedSlotPath[] paths, @NotNull Consumer<? super Pane> action) {
        if (paths == null) return;
        for (int windowSlot = 0; windowSlot < paths.length; windowSlot++) {
            DisplayedSlotPath path = paths[windowSlot];
            if (path != null) {
                path.forEachPane(action);
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
        // 视觉配置换了要重算; 完成通知只是把算好的结果刷出去, 不构成新的重算要求
        boolean cursorVisualDirty = this.cursorVisual.takeDirty();
        if (cursorVisualDirty) this.cursorRenderCell.dirty();
        this.cursorDirty |= cursorVisualDirty || this.cursorCompletionPending.getAndSet(false);
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
                Component title = this.effectiveTitle();
                menu.reopenWithTitle(title, protocolSlots, cursor);
                this.sentTitle = title;
            } else {
                menu.synchronize(protocolSlots, dirty, cursor, this.cursorDirty, full);
            }
            this.commitStructureBarriers(menu);
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
            SparrowUI.getInstance().handleException("Failed to synchronize Window", throwable);
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
                        if (selection != null && !ItemUtils.isContentEqual(selection.observedBundle(), rendered)) {
                            this.bundleSelections[windowSlot] = null;
                        }
                    }
                    localSlots[windowSlot] = rendered;
                } catch (Throwable throwable) {
                    SparrowUI.getInstance().handleException("Failed to render Window slot " + windowSlot, throwable);
                    localSlots[windowSlot] = localSlots[windowSlot] == null ? ItemUtils.EMPTY : localSlots[windowSlot];
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
            // 光标内容变了, 基于旧内容算出的异步视觉一并作废
            MenuHandle.CursorSnapshot previous = this.localCursor;
            if (previous == null || !ItemUtils.isContentEqual(previous.actual(), actual)) {
                this.cursorRenderCell.reset();
            }
            // 光标映射展示, 没有映射时按菜单实际光标显示
            ResolvedVisual visual = this.cursorVisual.visualize(actual);
            // 当场算得出的提供器由渲染格自己短路, 算不出的走投影, 未完成时显示占位或菜单实际光标
            RenderCell.Intent intent = visual == null
                    ? new RenderCell.Intent.Direct(actual)
                    : new RenderCell.Intent.Projected(visual.sourceKey(), visual.provider(), visual.placeholder(), actual);
            return new MenuHandle.CursorSnapshot(actual, ItemUtils.copyOrEmpty(this.cursorRenderCell.render(intent)));
        } catch (Throwable throwable) {
            SparrowUI.getInstance().handleException("Failed to render Window cursor visualizer", throwable);
            return new MenuHandle.CursorSnapshot(actual, actual.clone());
        }
    }

    @NotNull
    @Override
    public CompletableFuture<Window> navigate(@NotNull Window next) {
        return this.manager.navigate(this, this.requireSameViewer(next));
    }

    @NotNull
    @Override
    public CompletableFuture<Window> navigate(@NotNull CompletionStage<? extends Window> next) {
        return this.manager.navigateLater(this, next);
    }

    // 校验下一扇 Window 与本窗属于同一名玩家, 并取出实现视图.
    @NotNull
    AbstractWindow<?> requireSameViewer(@NotNull Window next) {
        if (next.viewer() != this.viewer) {
            throw new IllegalArgumentException("next Window belongs to another viewer");
        }
        return (AbstractWindow<?>) next;
    }

    @NotNull
    @Override
    public CompletableFuture<Window> back() {
        return this.manager.back(this, false);
    }

    @NotNull
    @Override
    public CompletableFuture<Window> backOrClose() {
        return this.manager.back(this, true);
    }

    @NotNull
    @Override
    public CompletableFuture<CloseResult> close() {
        return this.manager.close(this);
    }

    // 关闭已打开的 Window.
    boolean closeOnViewerEntity(InventoryCloseEvent.Reason reason) {
        if (!this.open) return false;

        Throwable failure = this.teardownOnEntity(reason);
        this.fireCloseHandlers(reason);
        ThrowableUtils.throwIfUnchecked(failure);
        return true;
    }

    /**
     * 服务器已经接管关闭流程后, 只回收 Window 的本地资源并通知关闭处理器.
     * 该路径不再次主动关闭容器.
     *
     * @param reason Bukkit 容器关闭原因
     */
    void closeAfterInventoryEvent(InventoryCloseEvent.Reason reason) {
        if (!this.open) return;
        Throwable failure = this.teardownOnEntity(null);
        this.fireCloseHandlers(reason);
        ThrowableUtils.throwIfUnchecked(failure);
    }

    // 关闭本次打开的菜单, 并清理菜单, tick 任务和显示路径.
    // reason 为 null 表示容器关闭已由 Paper 或 调度器注销任务 接管.
    @Nullable
    private Throwable teardownOnEntity(@Nullable InventoryCloseEvent.Reason reason) {
        // 使后续输入与 tick 立即失效
        this.open = false;
        this.generation++;
        this.cursorRenderCell.reset();
        this.cursorCompletionPending.set(false);
        this.clickInterpreter.reset();
        Arrays.fill(this.bundleSelections, null);

        ScheduledTask previousTickTask = this.tickTask;
        M previousMenu = this.menuHandle;
        DisplayedSlotPath[] previousPaths = this.paths;
        this.tickTask = null;
        this.menuHandle = null;
        this.paths = null;
        this.refreshInventories = null;
        this.refreshPanes = null;
        this.refreshDeclarations = null;
        this.refreshSequences = null;
        this.refreshSequenceMembers = null;
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
        try {
            this.windowVisual.finishAnimations(AnimationHandle.FinishReason.WINDOW_CLOSED);
        } catch (Throwable throwable) {
            failure = ThrowableUtils.combine(failure, throwable);
        }
        try {
            this.finishTitleAnimations(AnimationHandle.FinishReason.WINDOW_CLOSED);
        } catch (Throwable throwable) {
            failure = ThrowableUtils.combine(failure, throwable);
        }
        // 绑定只在打开期有效: 摘掉当次订阅, 声明留到下次打开再挂
        try {
            this.bindings.suspendAll();
        } catch (Throwable throwable) {
            failure = ThrowableUtils.combine(failure, throwable);
        }
        return closePaths(previousPaths, failure);
    }

    // 运行关闭处理器.
    private void fireCloseHandlers(InventoryCloseEvent.Reason reason) {
        this.closeHandlers.forEachIsolated(
                handler -> handler.accept(reason),
                "Failed to handle Window close",
                SparrowUI.getInstance()::handleException
        );
    }

    // 调度器意外退役时回收本地资源, 不发客户端关闭包, 也不调用用户关闭处理器.
    boolean retireSession() {
        boolean wasOpen = this.open;
        Throwable failure = this.teardownOnEntity(null);
        if (failure != null) {
            SparrowUI.getInstance().handleException("Failed to retire Window session", failure);
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
    protected final <T> CompletableFuture<T> submit(@NotNull Callable<T> action, @NotNull Callable<T> retiredAction) {
        return this.manager.submit(this, action, retiredAction);
    }

    // 上报 Window 处理器里的异常
    protected final void report(@NotNull String message, @NotNull Throwable throwable) {
        SparrowUI.getInstance().handleException(message, throwable);
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
     * 默认只包含经未冻结协议槽展示的 Inventory 槽位: Pane 冻结槽和 Window 虚拟槽位展示的槽位不参与,
     * 未被 Pane 展示的槽位同样不参与, 转移与收集都不会穿透它们.
     * 开启 Inventory 的 includeObscuredSlots 后其未展示槽位也参与, 但 Pane 冻结槽展示的槽位始终不参与.
     *
     * @return 去重后的连接 Inventory 及参与槽位
     */
    private List<ClickSemantics.LinkedInventory> collectLinkedInventories() {
        LinkedHashMap<SparrowInventory, BitSet> visible = new LinkedHashMap<>();
        this.forEachLinkedInventory(this.paths, true, link ->
                visible.computeIfAbsent(link.inventory(), inventory -> new BitSet(inventory.size())).set(link.slot()));
        // Pane 声明关联的 Inventory, 没有任何槽位被展示, 可见集留空.
        this.forEachPaneLinkedInventory(this.paths, inventory ->
                visible.computeIfAbsent(inventory, target -> new BitSet(target.size())));

        List<ClickSemantics.LinkedInventory> linked = new ArrayList<>(visible.size());
        visible.forEach((inventory, slots) -> {
            // 已经退役的 Inventory 背后没有容器了, 不作为快速转移与双击收集的目标.
            if (inventory.retired()) return;
            BitSet participating = inventory.includeObscuredSlots() ? this.withObscuredSlots(inventory, slots) : slots;
            // 一个槽位都不参与就不进目标列表.
            if (!participating.isEmpty()) {
                linked.add(new ClickSemantics.LinkedInventory(inventory, participating));
            }
        });
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
            Element.InventoryLink link = path.inventoryLink();
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
            Element.InventoryLink link = AbstractWindow.this.requirePath(windowSlot).inventoryLink();
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
                SparrowUI.getInstance().handleException("Failed to render Window slot " + windowSlot, throwable);
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
            Element.InventoryLink link = path.inventoryLink();
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
            return menu != null ? menu.cursor() : ItemUtils.EMPTY;
        }

        @Override
        @NotNull
        public Object unsafeCursor() {
            M menu = AbstractWindow.this.menuHandle;
            return menu != null ? menu.unsafeCursor() : ItemStackProxy.EMPTY;
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
        private final boolean fireBukkitEvents;

        // Bukkit 事件在没有插件监听时不派发, Paper 自己派发事件前也是这么判断的.
        InteractionGuard(MenuHandle menu, org.bukkit.event.HandlerList eventHandlers) {
            this.menu = menu;
            this.generation = AbstractWindow.this.generation;
            this.stateId = menu.stateId();
            this.pathRevision = AbstractWindow.this.interactionPathRevision.get();
            this.fireBukkitEvents = SparrowUI.getInstance().fireBukkitInventoryEvents() && eventHandlers.getRegisteredListeners().length != 0;
        }

        @Override
        public boolean stillValid() {
            return AbstractWindow.this.isInteractionCurrent(this.generation, this.menu, this.stateId, this.pathRevision);
        }

        @Override
        public boolean firesBukkitEvents() {
            return this.fireBukkitEvents;
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
            super(menu, InventoryClickEvent.getHandlerList());
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
            super(menu, InventoryDragEvent.getHandlerList());
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
