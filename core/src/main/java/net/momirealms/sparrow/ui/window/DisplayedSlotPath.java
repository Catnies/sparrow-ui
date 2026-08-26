package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.click.ClickSemantics;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDrag;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSlotAttachment;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import net.momirealms.sparrow.ui.visual.ResolvedVisual;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class DisplayedSlotPath implements AutoCloseable {
    private static final RenderCell.Intent EMPTY_INTENT = new RenderCell.Intent.Direct(ItemUtils.EMPTY);

    // 路径起点
    private final Window window;    // 所属 Window
    private final int windowSlot;   // 本路径服务的 Window 槽位
    private final Pane rootPane;      // 路径起点的根 Pane
    private final int rootSlot;     // 路径起点在根 Pane 中的槽位
    // 渲染与视觉订阅
    private final RenderContext renderContext;  // 该 Window 槽位专用的渲染上下文
    private final RenderCell renderCell; // 失效驱动的渲染投影
    private final Subscription windowVisualSubscription; // 本 Window 槽位视觉配置变化的失效订阅
    // 解析生命周期
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RESOLVING);
    private PathState current;  // 当前已解析出的路径
    // 临时数据
    @Nullable private volatile Object remembered;   // 最近一次渲染记下的东西, 终点 Item 换了或关闭了 Window 就清理

    DisplayedSlotPath(@NotNull Window window, int windowSlot, @NotNull Pane rootPane, int rootSlot) {
        this.window = window;
        this.windowSlot = windowSlot;
        this.rootPane = rootPane;
        this.rootSlot = rootSlot;
        this.renderContext = new RenderContext(window, windowSlot, this::remember);
        this.renderCell = new RenderCell(
                this.renderContext,
                () -> this.onDirty(Invalidation.COMPLETION),
                throwable -> SparrowUI.getInstance().handleException("Failed to render asynchronous Window slot " + windowSlot, throwable)
        );
        this.windowVisualSubscription = window.visual().attach(windowSlot, () -> this.onDirty(Invalidation.RENDER));

        try {
            this.resolve();
            this.window.notifyUpdate(windowSlot);
        } catch (RuntimeException | Error throwable) {
            ThrowableUtils.captureUnchecked(throwable, this::close);
            throw throwable;
        }
    }

    /**
     * 重新跟随 Pane 链接解析这条显示路径.
     * <p>位置没变的层沿用原订阅. 要新建的部分全部成功后才替换旧路径.
     * 中途任何订阅失败, 新路径只关掉自己刚建的那几层, 沿用的层仍旧归旧路径, 旧路径继续工作.
     */
    void resolve() {
        this.beginResolve();
        try {
            this.resolveLayers();
        } catch (RuntimeException | Error throwable) {
            // 解析失败, 保留"还要再解析一次"的要求, 下次读取路径时再试.
            while (true) {
                Phase phase = this.phase.get();
                if (phase == Phase.CLOSED || this.phase.compareAndSet(phase, Phase.ACTIVE_RESOLVE_REQUIRED)) {
                    break;
                }
            }
            throw throwable;
        }
        if (this.endResolve()) {
            // 解析期间有通知到过, 这里补标一次脏槽位
            this.window.notifyUpdate(this.windowSlot);
        }
    }

    // 解析期间的通知先记在 phase 上, 结束时一并处理.
    private void beginResolve() {
        while (true) {
            Phase phase = this.phase.get();
            if (phase == Phase.CLOSED) {
                throw new IllegalStateException("displayed path is closed");
            }
            if (this.phase.compareAndSet(phase, Phase.RESOLVING)) {
                return;
            }
        }
    }

    // 结束一次解析, 解析期间收到过通知, 需要标记脏槽位时返回 true.
    private boolean endResolve() {
        while (true) {
            Phase phase = this.phase.get();
            if (phase == Phase.CLOSED) {
                return false;
            }
            // 解析期间来过结构通知, 那就还得再解析一次
            Phase settled = phase == Phase.RESOLVING_RESOLVE_PENDING
                    ? Phase.ACTIVE_RESOLVE_REQUIRED
                    : Phase.ACTIVE;
            if (this.phase.compareAndSet(phase, settled)) {
                return phase != Phase.RESOLVING;
            }
        }
    }

    // 先沿用位置不变的订阅, 再重建剩余路径.
    private void resolveLayers() {
        PathState previous = this.current;
        int reusable = this.reusableDepth(previous);

        // 每一层的位置和终点元素都没变, 只重新计算冻结状态
        if (previous != null && reusable == previous.depth && this.leafUnchanged(previous)) {
            boolean previousFrozen = previous.frozen;
            this.refreshFrozen(previous);
            // 冻结语义变化会作废旧点击候选, 并挡住客户端基于旧状态发出的交互
            if (previousFrozen != previous.frozen && this.window instanceof AbstractWindow<?> abstractWindow) {
                abstractWindow.notifyInteractionPathChanged();
                abstractWindow.notifyInteractionStructureChanged(this.windowSlot);
            }
            return;
        }

        PathState next = new PathState();
        next.reuse(previous, reusable);
        try {
            this.prepare(next, reusable);
        } catch (RuntimeException | Error throwable) {
            // 失败时关掉自己新建的层, 沿用的层还归旧路径, 旧路径不受影响.
            next.ownedFrom = reusable;
            ThrowableUtils.captureUnchecked(throwable, next::close);
            throw throwable;
        }
        this.refreshFrozen(next);

        boolean interactionChanged = previous != null
                && (previous.frozen != next.frozen || !Objects.equals(previous.inventoryLink, next.inventoryLink));
        // 来源身份只担保这一层配置没换, 翻页仍可能把槽位改指到另一个 Inventory 槽.
        // 配置对象却是同一个, 所以终点换了要自己作废上一个终点算出来的结果.
        boolean leafChanged = previous != null && !sameLeaf(previous.leafElement, next.leafElement);
        // 沿用深度和新旧层数三者相等, 才说明经过的 Pane 一个没换. 终点 Inventory 也没换时,
        // Window 那份刷新名单就还是对的. 只换 Item 终点的路径(投影刷新就是这样)不必惊动它.
        boolean refreshTargetsStale = previous == null
                || reusable != previous.depth
                || reusable != next.depth
                || !Objects.equals(previous.inventoryLink, next.inventoryLink);
        try {
            if (previous != null) {
                // 沿用的层已经交给新路径, 旧路径只关掉自己独有的那几层
                previous.ownedFrom = reusable;
                previous.close();
            }
        } finally {
            this.current = next;
            if (leafChanged) {
                this.renderCell.reset();
                this.remembered = null;     // 上一个终点记下的东西不留给下一个
            }
            if (this.window instanceof AbstractWindow<?> abstractWindow) {
                if (refreshTargetsStale) {
                    abstractWindow.invalidateRefreshTargets();
                }
                if (interactionChanged) {
                    abstractWindow.notifyInteractionPathChanged();
                }
                // 终点身份换了也算结构变化, 玩家看着的那个按钮已经不是现在这个了
                if (interactionChanged || leafChanged) {
                    abstractWindow.notifyInteractionStructureChanged(this.windowSlot);
                }
            }
        }
    }

    /**
     * 沿旧路径逐层比对, 返回有多少层的订阅可以直接沿用.
     * <p>这里比较每层订阅的 Pane 与槽位. 订阅盯的是位置,
     * 位置上换了元素正是它要通知的事, 位置没变就不必重订.
     *
     * @param previous 上一次解析出的路径, 首次解析时为 null
     * @return 可以沿用的层数
     */
    private int reusableDepth(PathState previous) {
        if (previous == null || previous.depth == 0) {
            return 0;
        }

        // 第 0 层订的就是 rootPane 的 rootSlot, 这两个值不会变, 所以总能沿用
        int reusable = 1;
        Pane pane = this.rootPane;
        int paneSlot = this.rootSlot;
        while (reusable < previous.depth) {
            // 这一层现在指向哪里, 决定下一层还在不在原来的位置
            if (!(pane.element(paneSlot) instanceof Element.PaneLink link)) {
                break;
            }
            if (previous.panes[reusable] != link.pane() || previous.paneSlots[reusable] != link.slot()) {
                break;
            }
            pane = link.pane();
            paneSlot = link.slot();
            reusable++;
        }
        return reusable;
    }

    // 在每一层都能沿用的前提下, 再看终点元素有没有变.
    private boolean leafUnchanged(@NotNull PathState previous) {
        int leafDepth = previous.depth - 1;
        Element element = previous.panes[leafDepth].element(previous.paneSlots[leafDepth]);
        return sameLeaf(element, previous.leafElement);
    }

    // 判断两个终点元素指的是不是同一个终点.
    private static boolean sameLeaf(@Nullable Element left, @Nullable Element right) {
        if (left instanceof Element.Item(var leftItem) && right instanceof Element.Item(var rightItem)) {
            return leftItem == rightItem;
        }
        return Objects.equals(left, right);
    }

    /**
     * 从指定层开始跟随 PaneLink, 订阅沿途每个 Pane 槽位与它的视觉失效通知, 以及最终 Item.
     * <p>遇到空槽位或 Item 就停. 遇到重复的 Pane 说明链接成环, 直接失败.
     *
     * @param next 正在准备的新路径, 已经沿用了 {@code from} 之前的层
     * @param from 需要重新订阅的第一层
     */
    private void prepare(PathState next, int from) {
        Pane pane;
        int paneSlot;

        if (from == 0) {
            pane = this.rootPane;
            paneSlot = this.rootSlot;
        } else {
            // 沿用层的终点已经不是 PaneLink 时, 路径到这里结束
            Element element = next.panes[from - 1].element(next.paneSlots[from - 1]);
            if (!(element instanceof Element.PaneLink link)) {
                this.attachLeaf(next, element);
                return;
            }
            pane = link.pane();
            paneSlot = link.slot();
        }

        while (true) {
            // 链接成环直接失败
            if (next.contains(pane)) {
                throw new IllegalStateException("Pane link cycle detected at depth " + next.depth + " for local slot " + paneSlot);
            }

            // 槽位内容变化后重新解析路径
            // 取消订阅和派发通知可能同时发生, discarded 会屏蔽丢弃时挤进来的通知.
            // 视觉失效订阅先挂, 它只弱持有回执, 之后的订阅失败时它随本次解析的引用一起被回收.
            AtomicBoolean discarded = new AtomicBoolean();
            Subscription visualSubscription = pane.visual().attach(paneSlot, () -> {
                if (!discarded.get()) {
                    this.onDirty(Invalidation.RENDER);
                }
            });
            PaneSlotAttachment attachment = pane.attach(paneSlot, ignoredInvalidation -> {
                if (!discarded.get()) {
                    this.onDirty(Invalidation.STRUCTURE);
                }
            });
            next.add(pane, paneSlot, attachment, visualSubscription, discarded);

            // PaneLink 继续深入, 其余元素成为终点
            if (attachment.element() instanceof Element.PaneLink link) {
                pane = link.pane();
                paneSlot = link.slot();
                continue;
            }
            this.attachLeaf(next, attachment.element());
            return;
        }
    }

    /**
     * 记录路径终点并为它建立订阅.
     * <p>终点订阅不会跨解析沿用, 所以它们直接看所属路径的 {@code resourcesClosed}
     * 判断自己还算不算数, 不必像每一层那样各带一个标志.
     *
     * @param next 正在准备的新路径
     * @param leaf 终点元素
     */
    private void attachLeaf(PathState next, Element leaf) {
        next.leafElement = leaf;
        switch (leaf) {
            case Element.Item(var item) -> {
                next.item = item;
                next.itemAttachment = item.attach(this.window, ignore -> {
                    if (!next.resourcesClosed) {
                        this.onDirty(Invalidation.RENDER);
                    }
                });
            }
            case Element.InventoryLink link -> {
                next.inventoryLink = link;
                next.inventorySubscription = link.inventory().subscribePostUpdate(event -> {
                    if (next.resourcesClosed) return;
                    // 事件使用当前订阅 Inventory 的槽位坐标, 只需检查当前路径连接的槽号.
                    if (event.changeAt(link.slot()) != null) {
                        this.onDirty(Invalidation.CONTENT);
                    }
                });
                next.inventoryVisualSubscription = link.inventory().visual().attach(link.slot(), () -> {
                    if (!next.resourcesClosed) {
                        // 退役同时把内容清空, 那是内容变化不是视觉变化, 基于退役前内容算出的结果, 连同在飞的那次计算, 都必须作废.
                        this.onDirty(link.inventory().retired() ? Invalidation.CONTENT : Invalidation.RENDER);
                    }
                });
            }
            case Element.PaneLink ignoredLink -> throw new IllegalStateException("pane link cannot be a path leaf");
            case Element.Empty ignoredEmpty -> {
            }
        }
    }

    private void refreshFrozen(@NotNull PathState state) {
        boolean frozen = false;
        for (int index = 0; index < state.depth; index++) {
            frozen |= state.panes[index].frozen();
        }
        state.frozen = frozen;
    }

    // Pane 结构变过时先解析, 内容变化沿用现有路径.
    @NotNull
    private PathState currentState() {
        this.requireOpen();
        if (this.current == null) {
            throw new IllegalStateException("displayed path has not been resolved");
        }
        if (this.phase.get() == Phase.ACTIVE_RESOLVE_REQUIRED) {
            this.resolve();
        }
        return this.current;
    }

    private void requireOpen() {
        if (this.phase.get() == Phase.CLOSED) {
            throw new IllegalStateException("displayed path is closed");
        }
    }

    /**
     * 生成当前槽位应显示的 ItemStack.
     * <p>意图按以下优先级装配.
     * <ol>
     *   <li>本 Window 的槽位视觉映射, 它只属于本查看者, 盖在整条路径的最外层.
     *   <li>沿途每层 Pane 的视觉映射, 自根向叶求值, 命中的层盖住路径终点.
     *   <li>若路径终点为 InventoryLink, 显示 Inventory 的槽位映射,
     *       没有映射就显示内容, 最后显示 Inventory 的背景. 没有背景就保持空槽.
     *   <li>若路径终点为 Item, 显示该 Item.
     *   <li>若路径终点为 Empty, 回退为最深层 Pane 的背景.
     *   <li>若仍无结果, 返回空物品作为最终兜底.
     * </ol>
     *
     * @return 当前槽位应显示的 ItemStack, 不会为 {@code null}
     */
    @NotNull ItemStack render() {
        PathState state = this.currentState();
        // Inventory 终点提供同步可读内容, 其余终点没有
        // 单次读, 能不复制就不复制, 读出来的内容一路交给视觉映射与渲染结果, 全程只读.
        ItemStack itemStack = null;
        if (state.inventoryLink != null) {
            SparrowInventory inventory = state.inventoryLink.inventory();
            int slot = state.inventoryLink.slot();
            itemStack = inventory instanceof ReferencingInventory ? inventory.itemAt(slot) : inventory.unsafeItemAt(slot);
        }
        // Window 槽位层只属于本查看者, 优先盖住整条路径
        ResolvedVisual windowVisual = this.window.visual().visualize(this.windowSlot, itemStack);
        if (windowVisual != null) {
            return this.renderProjected(windowVisual, itemStack);
        }
        // 沿途每层 Pane 的视觉映射自根向叶试探, 越靠外的层越优先
        for (int index = 0; index < state.depth; index++) {
            ResolvedVisual paneVisual = state.panes[index].visual().visualize(state.paneSlots[index], itemStack);
            if (paneVisual != null) {
                return this.renderProjected(paneVisual, itemStack);
            }
        }
        // InventoryLink
        if (state.inventoryLink != null) {
            ResolvedVisual visual = state.inventoryLink.inventory().visual()
                    .visualizeWithBackground(state.inventoryLink.slot(), itemStack);
            // 当场算得出的提供器由渲染格自己短路, 算不出的走投影, 未完成时显示占位或该槽真实内容
            return visual != null
                    ? this.renderProjected(visual, itemStack)
                    : this.renderCell.render(itemStack == null ? EMPTY_INTENT : new RenderCell.Intent.Direct(itemStack));
        }
        // Item
        if (state.item != null) {
            return this.renderCell.render(new RenderCell.Intent.Projected(state.item.getItemProvider(), state.item.getPlaceholder(), null));
        }
        // 空终点回退到沿途最深层的 Pane 背景
        for (int index = state.depth - 1; index >= 0; index--) {
            ItemProvider background = state.panes[index].visual().background();
            if (background != null) {
                return this.renderCell.render(new RenderCell.Intent.Projected(background, null, null));
            }
        }
        return this.renderCell.render(EMPTY_INTENT);
    }

        // 命中的视觉层交给渲染格投影. 兜底使用终点的同步内容, null 按空物品处理.
    @NotNull
    private ItemStack renderProjected(@NotNull ResolvedVisual visual, @Nullable ItemStack itemStack) {
        return this.renderCell.render(new RenderCell.Intent.Projected(
                visual.sourceKey(), visual.provider(), visual.placeholder(), itemStack));
    }

    /**
     * 处理一次失效通知.
     * <p>任意线程都可能调用, 这里只改 {@link Phase} 和脏标记.
     *
     * @param invalidation 本次通知的来路
     */
    private void onDirty(@NotNull Invalidation invalidation) {
        switch (invalidation) {
            // 内容变了, 基于旧内容算出的异步视觉一并作废.
            case CONTENT -> this.renderCell.reset();
            // 完成通知送来的是已经算好的结果, 不是数据变化, 不必再要求重算
            case COMPLETION -> { }
            default -> this.renderCell.dirty();
        }
        boolean structural = invalidation == Invalidation.STRUCTURE;
        while (true) {
            Phase phase = this.phase.get();
            switch (phase) {
                // 正在解析, 先把通知记下来, 解析结束时一起处理
                case RESOLVING, RESOLVING_RENDER_PENDING -> {
                    Phase pending = structural ? Phase.RESOLVING_RESOLVE_PENDING : Phase.RESOLVING_RENDER_PENDING;
                    if (phase == pending || this.phase.compareAndSet(phase, pending)) {
                        return;
                    }
                }
                // 结构变了要先重新解析, 内容变了直接标脏就行
                case ACTIVE -> {
                    if (structural && !this.phase.compareAndSet(phase, Phase.ACTIVE_RESOLVE_REQUIRED)) {
                        continue;
                    }
                    this.window.notifyUpdate(this.windowSlot);
                    return;
                }
                case ACTIVE_RESOLVE_REQUIRED -> {
                    this.window.notifyUpdate(this.windowSlot);
                    return;
                }
                case CLOSED, RESOLVING_RESOLVE_PENDING -> {
                    return;
                }
            }
        }
    }

    // 渲染替这位玩家这个槽位记下一个东西, 下一次渲染覆盖.
    private void remember(@Nullable Object value) {
        this.remembered = value;
    }

    @Nullable
    Object remembered() {
        return this.remembered;
    }

    void handleClick(@NotNull ItemClick click) {
        PathState state = this.currentState();
        if (!state.frozen && !this.windowFrozen() && state.item != null) {
            state.item.handleClick(click);
        }
    }

    void handleDrag(@NotNull ItemDrag drag) {
        PathState state = this.currentState();
        if (!state.frozen && !this.windowFrozen() && state.item != null) {
            state.item.handleDrag(drag);
        }
    }

    boolean hasInteractiveItem() {
        PathState state = this.currentState();
        return !state.frozen && !this.windowFrozen() && state.item != null;
    }

    void handleBundleSelect(@NotNull BundleSelectClick select) {
        PathState state = this.currentState();
        if (state.frozen || this.windowFrozen()) {
            return;
        }
        if (state.inventoryLink != null) {
            ClickSemantics.dispatchBundleSelectEvent(state.inventoryLink.inventory(), state.inventoryLink.slot(), select);
        } else if (state.item != null) {
            state.item.handleBundleSelect(select);
        }
    }

    // 强制处理还没解析的 Pane 变化, 让 Window 在提交旧的点击候选前看到交互终点或冻结状态的改变.
    void refreshInteractionState() {
        this.currentState();
    }

    @Nullable
    Element.InventoryLink inventoryLink() {
        return this.currentState().inventoryLink;
    }

    boolean frozen() {
        return this.windowFrozen() || this.currentState().frozen;
    }

    // Window 侧的单槽冻结与沿途 Pane 冻结同待遇, 任一生效本路径即按冻结处理.
    private boolean windowFrozen() {
        return this.window.frozenAt(this.windowSlot);
    }

    void forEachPane(@NotNull Consumer<? super Pane> action) {
        PathState state = this.currentState();
        for (int index = 0; index < state.depth; index++) {
            action.accept(state.panes[index]);
        }
    }

    boolean isClosed() {
        return this.phase.get() == Phase.CLOSED;
    }

    /**
     * 关闭当前路径并取消所有 Pane 和 Item 订阅.
     * 重复调用安全.
     */
    @Override
    public void close() {
        if (this.phase.getAndSet(Phase.CLOSED) == Phase.CLOSED) {
            return;
        }
        this.renderCell.close();
        this.windowVisualSubscription.close();
        this.remembered = null;
        PathState previous = this.current;
        this.current = null;
        if (previous != null) {
            // 整条路径都要关掉, 没有哪一层需要留给别人
            previous.ownedFrom = 0;
            previous.close();
        }
    }

    /**
     * 一次失效通知的来路, 决定要不要重新解析路径, 要不要重新计算显示来源.
     */
    private enum Invalidation {
        STRUCTURE,  // Pane 槽位变了, 路径结构可能不同, 要重新解析
        RENDER,     // 终点的 Item, 沿途 Pane 或 Inventory 的视觉配置变了, 重新渲染就够
        CONTENT,    // 终点 Inventory 槽位的内容变了, 基于旧内容算出的异步视觉一并作废
        COMPLETION  // RenderCell 的完成通知, 只消费已经算好的结果
    }

    /**
     * 路径当前处在哪一步, 以及解析期间收到但还没来得及处理的通知.
     */
    private enum Phase {
        RESOLVING,                  // 正在解析, 这期间到达的通知先记在这里
        RESOLVING_RENDER_PENDING,   // 解析期间来过内容通知, 解析完要标记脏槽位
        RESOLVING_RESOLVE_PENDING,  // 解析期间来过结构通知, 解析完还得再解析一次
        ACTIVE,                     // 正常显示中
        ACTIVE_RESOLVE_REQUIRED,    // 结构变过了, 下次读取路径之前先重新解析
        CLOSED                      // 已关闭, 之后到达的通知一律忽略
    }

    /**
     * 一次解析的结果, 包含沿途层级, 终点和冻结状态.
     *
     * <p>这里只保存结构和订阅. 订阅回调都绑在 {@link DisplayedSlotPath} 上,
     * 所以沿用到下一次解析的层照样有效.
     */
    private static final class PathState implements AutoCloseable {
        // Pane 路径与逐层订阅
        private Pane[] panes = new Pane[4]; // 根 Pane -> 最深层 Pane
        private int[] paneSlots = new int[4];
        private PaneSlotAttachment[] paneAttachments = new PaneSlotAttachment[4];
        private Subscription[] paneVisualSubscriptions = new Subscription[4];
        private AtomicBoolean[] discardedLayers = new AtomicBoolean[4];
        private int depth;
        private int ownedFrom; // 关闭时从这一层开始取消订阅, 更靠前的层已经交给新路径

        // 终点元素
        private Element leafElement;

        // Item 部分, 与 Inventory 链接互斥
        private Item item;
        private ItemAttachment itemAttachment = ItemAttachment.PASSIVE;

        // Inventory 链接部分, 与 Item 互斥
        private Element.InventoryLink inventoryLink;
        private Subscription inventorySubscription;
        private Subscription inventoryVisualSubscription;

        // 终点状态与生命周期
        private boolean frozen;
        // 终点订阅用它过滤关闭竞态, 同时保证 close 幂等
        private volatile boolean resourcesClosed;

        /**
         * 沿用旧路径开头若干层的订阅.
         *
         * @param source 上一次解析出的路径, {@code count} 为 0 时允许为 null
         * @param count 沿用的层数
         */
        private void reuse(PathState source, int count) {
            for (int index = 0; index < count; index++) {
                this.add(source.panes[index], source.paneSlots[index], source.paneAttachments[index], source.paneVisualSubscriptions[index], source.discardedLayers[index]);
            }
        }

        /**
         * 检查 Pane 是否已经出现在当前路径中, 用于拒绝循环链接.
         *
         * @param pane 要检查的 Pane
         * @return 已经出现时为 true
         */
        private boolean contains(Pane pane) {
            for (int index = 0; index < this.depth; index++) {
                if (this.panes[index] == pane) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 记录一层 Pane, 它的槽位及两条订阅, 必要时扩容数组.
         *
         * @param pane 路径中的 Pane
         * @param paneSlot 该层订阅的 Pane 槽位
         * @param attachment Pane 槽位订阅
         * @param visualSubscription 该层的视觉失效订阅
         * @param discarded 这一层被丢弃后置起来的标志
         */
        private void add(Pane pane, int paneSlot, PaneSlotAttachment attachment, Subscription visualSubscription, AtomicBoolean discarded) {
            if (this.depth == this.panes.length) {
                int newLength = this.depth * 2;
                this.panes = Arrays.copyOf(this.panes, newLength);
                this.paneSlots = Arrays.copyOf(this.paneSlots, newLength);
                this.paneAttachments = Arrays.copyOf(this.paneAttachments, newLength);
                this.paneVisualSubscriptions = Arrays.copyOf(this.paneVisualSubscriptions, newLength);
                this.discardedLayers = Arrays.copyOf(this.discardedLayers, newLength);
            }
            this.panes[this.depth] = pane;
            this.paneSlots[this.depth] = paneSlot;
            this.paneAttachments[this.depth] = attachment;
            this.paneVisualSubscriptions[this.depth] = visualSubscription;
            this.discardedLayers[this.depth] = discarded;
            this.depth++;
        }

        /**
         * 关闭本路径独有的订阅, 并清掉 Item 和 Pane 引用.
         * <p>{@code ownedFrom} 之前的层已经交给新路径, 只断引用不取消订阅.
         * 某个订阅关闭失败也会继续关其余的, 最后再把收集到的异常抛出来. 重复调用安全.
         */
        @Override
        public void close() {
            if (this.resourcesClosed) return;
            this.resourcesClosed = true;

            // 先断开自身持有的状态引用, 再调用外部 close
            ItemAttachment previousItemAttachment = this.itemAttachment;
            Subscription previousInventorySubscription = this.inventorySubscription;
            Subscription previousInventoryVisualSubscription = this.inventoryVisualSubscription;
            this.itemAttachment = ItemAttachment.PASSIVE;
            this.inventorySubscription = null;
            this.inventoryVisualSubscription = null;
            this.item = null;
            this.inventoryLink = null;
            this.leafElement = null;

            Throwable failure = ThrowableUtils.captureUnchecked(null, previousItemAttachment::close);

            if (previousInventorySubscription != null) {
                failure = ThrowableUtils.captureUnchecked(failure, previousInventorySubscription::close);
            }

            if (previousInventoryVisualSubscription != null) {
                failure = ThrowableUtils.captureUnchecked(failure, previousInventoryVisualSubscription::close);
            }

            // 从最深层 Pane 向根 Pane 逆序取消订阅
            for (int index = this.depth - 1; index >= 0; index--) {
                PaneSlotAttachment paneAttachment = this.paneAttachments[index];
                Subscription visualSubscription = this.paneVisualSubscriptions[index];
                AtomicBoolean discarded = this.discardedLayers[index];
                this.paneAttachments[index] = null;
                this.paneVisualSubscriptions[index] = null;
                this.discardedLayers[index] = null;
                this.panes[index] = null;
                if (index >= this.ownedFrom) {
                    // 先标成丢弃再取消订阅, 屏蔽同时到达的通知
                    discarded.set(true);
                    failure = ThrowableUtils.captureUnchecked(failure, paneAttachment::close);
                    failure = ThrowableUtils.captureUnchecked(failure, visualSubscription::close);
                }
            }
            this.depth = 0;

            ThrowableUtils.throwIfUnchecked(failure);
        }
    }
}
