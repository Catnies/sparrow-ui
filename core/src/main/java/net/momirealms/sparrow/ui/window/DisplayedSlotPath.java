package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSlotAttachment;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.inventory.ClickSemantics;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 记录 Window 中一个槽位当前显示的内容.
 * <p>它从根 Pane 的指定槽位出发, 跟随 {@link Element.PaneLink} 一层层进入子 Pane,
 * 直到找到 Item, Inventory连接或空槽位. Pane 变了要重新解析路径; 最终 Item 变了或
 * Inventory 有事务通知, 只需要重新渲染一次.
 */
final class DisplayedSlotPath implements AutoCloseable {
    private final Window window;    // 所属 Window
    private final int windowSlot;   // 本路径服务的 Window 槽位
    private final Pane rootPane;      // 路径起点的根 Pane
    private final int rootSlot;     // 路径起点在根 Pane 中的槽位
    private final RenderContext renderContext;  // 该 Window 槽位专用的渲染上下文
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RESOLVING);
    private PathState current;  // 当前已解析出的路径

    /**
     * 创建并立即解析一个 Window 槽位的显示路径.
     *
     * @param window 所属 Window
     * @param windowSlot Window 槽位编号
     * @param rootPane 显示路径的根 Pane
     * @param rootSlot 根 Pane 槽位编号
     */
    DisplayedSlotPath(@NotNull Window window, int windowSlot, @NotNull Pane rootPane, int rootSlot) {
        this.window = window;
        this.windowSlot = windowSlot;
        this.rootPane = rootPane;
        this.rootSlot = rootPane.size().checkSlot(rootSlot);
        this.renderContext = new RenderContext(window, windowSlot);

        try {
            this.resolve();
            this.window.notifyUpdate(windowSlot);
        } catch (RuntimeException | Error throwable) {
            // 首次解析失败: 关掉已建立的订阅再抛出, 避免泄漏
            ThrowableUtils.captureUnchecked(throwable, this::close);
            throw throwable;
        }
    }

    /**
     * 重新跟随 Pane 链接解析这条显示路径.
     * <p>位置没变的层直接沿用原来的订阅; 要新建的部分全部成功之后才换上去.
     * 中途任何订阅失败, 新路径只关掉自己刚建的那几层, 沿用的层仍旧归旧路径, 旧路径继续工作.
     */
    void resolve() {
        this.requireOpen();
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

    /**
     * 生成当前槽位应显示的 ItemStack, 按以下优先级查找:
     * <ol>
     *   <li>若路径终点为 InventoryLink, 优先显示 Inventory 的槽位映射,
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

        // InventoryLink: Inventory 的视觉层级和真实内容共同决定显示.
        if (state.inventoryLink != null) {
            SparrowInventory inventory = state.inventoryLink.inventory();
            int slot = state.inventoryLink.slot();
            ItemStack itemStack = inventory.itemAt(slot);
            ItemProvider visual = inventory.visualize(slot, itemStack);
            if (visual != null) {
                return ItemUtils.emptyIfNull(visual.provide(this.renderContext));
            }
            if (itemStack != null) {
                return itemStack;
            }
            return ItemStack.empty();
        }

        ItemProvider provider = state.item == null
                ? state.background == null ? ItemProvider.EMPTY : state.background
                : state.item.getItemProvider();
        return ItemUtils.emptyIfNull(provider.provide(this.renderContext));
    }

    /**
     * 把点击转发给路径终点的 Item.
     * 路径按冻结处理或终点不是 Item 时直接忽略.
     *
     * @param click 点击上下文
     */
    void handleClick(@NotNull ItemClick click) {
        PathState state = this.currentState();
        if (!state.frozen && !this.windowFrozen() && state.item != null) {
            state.item.handleClick(click);
        }
    }

    /**
     * 把拖拽手势转发给路径终点的 Item.
     * 路径按冻结处理或终点不是 Item 时直接忽略.
     *
     * @param drag 拖拽上下文
     */
    void handleDrag(@NotNull ItemDragClick drag) {
        PathState state = this.currentState();
        if (!state.frozen && !this.windowFrozen() && state.item != null) {
            state.item.handleDrag(drag);
        }
    }

    /**
     * 把收纳袋选择转发给路径终点的 Item.
     * 路径按冻结处理或终点不是 Item 时直接忽略.
     *
     * @param select Bundle 选择上下文
     */
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

    /**
     * 返回路径终点的 Inventory 连接,
     * 终点不是Inventory时返回 null.
     *
     * @return Inventory 连接, 没有时为 null
     */
    @org.jetbrains.annotations.Nullable
    Element.InventoryLink inventoryLink() {
        return this.currentState().inventoryLink;
    }

    /**
     * 返回路径是否按冻结处理: 经过已冻结 Pane, 或槽位被 Window 冻结;
     * 冻结槽不参与点击语义与 Item 分派.
     *
     * @return 路径按冻结处理时返回 true
     */
    boolean frozen() {
        return this.windowFrozen() || this.currentState().frozen;
    }

    // Window 侧的单槽冻结与沿途 Pane 冻结同待遇, 任一生效本路径即按冻结处理.
    private boolean windowFrozen() {
        return this.window.frozenAt(this.windowSlot);
    }

    /**
     * 返回显示路径终点的类型.
     * 路径按冻结处理时一律返回 {@link ItemDragClick.Kind#FROZEN}, 不再区分终点.
     *
     * @return 路径终点类型
     */
    @NotNull
    ItemDragClick.Kind kind() {
        PathState state = this.currentState();
        if (state.frozen || this.windowFrozen()) return ItemDragClick.Kind.FROZEN;
        if (state.inventoryLink != null) return ItemDragClick.Kind.INVENTORY;
        return state.item == null ? ItemDragClick.Kind.EMPTY : ItemDragClick.Kind.ITEM;
    }

    // 强制处理还没解析的 Pane 变化, 让 Window 在提交旧的点击候选前看到交互终点或冻结状态的改变.
    void refreshInteractionState() {
        this.currentState();
    }

    /**
     * 遍历路径当前经过的每一层 Pane, 从根 Pane 到最深层.
     *
     * @param action 对每一层 Pane 执行的操作
     */
    void forEachPane(@NotNull Consumer<? super Pane> action) {
        PathState state = this.currentState();
        for (int index = 0; index < state.depth; index++) {
            action.accept(state.panes[index]);
        }
    }

    /**
     * 返回路径是否已关闭.
     *
     * @return 已关闭时为 true
     */
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
        PathState previous = this.current;
        this.current = null;
        if (previous != null) {
            // 整条路径都要关掉, 没有哪一层需要留给别人
            previous.ownedFrom = 0;
            previous.close();
        }
    }

    /**
     * 解析路径, 先算出有多少层可以沿用, 再决定是只刷新背景, 还是重建剩下的部分.
     */
    private void resolveLayers() {
        PathState previous = this.current;
        int reusable = this.reusableDepth(previous);

        // 每一层的位置和终点元素都没变: 一个订阅都不用动, 只重新算背景和冻结状态
        if (previous != null && reusable == previous.depth && this.leafUnchanged(previous)) {
            boolean previousFrozen = previous.frozen;
            this.applyDecorations(previous);
            if (previousFrozen != previous.frozen) {
                this.notifyInteractionPathChanged();
            }
            return;
        }

        PathState next = new PathState();
        next.reuse(previous, reusable);
        try {
            this.prepare(next, reusable);
        } catch (RuntimeException | Error throwable) {
            // 建到一半失败: 只关掉自己新建的层, 沿用的层还归旧路径, 旧路径不受影响
            next.ownedFrom = reusable;
            ThrowableUtils.captureUnchecked(throwable, next::close);
            throw throwable;
        }
        this.applyDecorations(next);

        boolean interactionChanged = previous != null
                && (previous.frozen != next.frozen || !Objects.equals(previous.inventoryLink, next.inventoryLink));
        // 沿用深度和新旧层数三者相等, 才说明经过的 Pane 一个没换; 终点 Inventory 也没换的话,
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
            if (this.window instanceof AbstractWindow<?> abstractWindow) {
                if (refreshTargetsStale) {
                    abstractWindow.invalidateRefreshTargets();
                }
                if (interactionChanged) {
                    abstractWindow.notifyInteractionPathChanged();
                }
            }
        }
    }

    /**
     * 沿旧路径逐层比对, 返回有多少层的订阅可以直接沿用.
     * <p>比的是每一层订阅的是哪个 Pane 的哪个槽位, 不是槽位里放着什么: 订阅订的是位置,
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

    /**
     * 在每一层都能沿用的前提下, 再看终点元素有没有变.
     *
     * @param previous 上一次解析出的路径
     * @return 终点没变时返回 true
     */
    private boolean leafUnchanged(@NotNull PathState previous) {
        int leafDepth = previous.depth - 1;
        Element element = previous.panes[leafDepth].element(previous.paneSlots[leafDepth]);
        return Objects.equals(element, previous.leafElement);
    }

    /**
     * 重新算一遍整条路径的背景与冻结状态.
     * <p>背景取沿途最深层的非 null 值; 任何一层 Pane 冻结, 整条路径都视为经过已冻结 Pane.
     *
     * @param state 要刷新的路径
     */
    private void applyDecorations(@NotNull PathState state) {
        ItemProvider background = null;
        boolean frozen = false;
        for (int index = 0; index < state.depth; index++) {
            Pane pane = state.panes[index];
            ItemProvider paneBackground = pane.background();
            if (paneBackground != null) {
                background = paneBackground;
            }
            frozen |= pane.frozen();
        }
        state.background = background;
        state.frozen = frozen;
    }

    /**
     * 从指定层开始跟随 PaneLink, 订阅沿途的每个 Pane 槽位和最终 Item.
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
            // 沿用的最后一层现在指向哪里; 已经不是 PaneLink 就说明路径到它为止
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

            // 订阅这一层 Pane 的槽位: 槽位内容变了会要求重新解析路径.
            // 这一层将来被丢弃时会把 discarded 置起来 —— 取消订阅和派发通知可能同时发生,
            // 那一刻挤进来的通知要当作没收到.
            AtomicBoolean discarded = new AtomicBoolean();
            PaneSlotAttachment attachment = pane.attach(paneSlot, ignoredInvalidation -> {
                if (!discarded.get()) {
                    this.onInvalidation(true);
                }
            });
            next.add(pane, paneSlot, attachment, discarded);

            // 按槽位元素决定走向: PaneLink 继续深入, 其余三种都是终点
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
                        this.onInvalidation(false);
                    }
                });
            }
            case Element.InventoryLink link -> {
                next.inventoryLink = link;
                next.inventorySubscription = link.inventory().subscribePostUpdate(event -> {
                    if (next.resourcesClosed) {
                        return;
                    }
                    // 事件使用当前订阅 Inventory 的槽位坐标, 只需检查当前路径连接的槽号.
                    for (int i = 0; i < event.slotChanges().size(); i++) {
                        if (event.slotChanges().get(i).slot() == link.slot()) {
                            this.onInvalidation(false);
                            return;
                        }
                    }
                });
                next.visualSubscription = link.inventory().subscribeVisualInvalidation(slot -> {
                    if (!next.resourcesClosed && (slot == SparrowInventory.ALL_SLOTS || slot == link.slot())) {
                        this.onInvalidation(false);
                    }
                });
            }
            case Element.PaneLink ignoredLink -> throw new IllegalStateException("pane link cannot be a path leaf");
            case Element.Empty ignoredEmpty -> {
            }
        }
    }

    // 交互终点或冻结状态变了, 让 Window 作废那些在交互开始之后才改变的点击候选.
    private void notifyInteractionPathChanged() {
        if (this.window instanceof AbstractWindow<?> abstractWindow) {
            abstractWindow.notifyInteractionPathChanged();
        }
    }

    /**
     * 处理一次失效通知.
     * <p>任意线程都可能调用, 这里只改 {@link Phase} 和脏标记.
     *
     * @param structural true 表示通知来自 Pane 槽位, 路径结构可能变了, 要重新解析;
     *                   false 表示只来自终点的 Item 或 Inventory, 重新渲染就够了
     */
    private void onInvalidation(boolean structural) {
        while (true) {
            Phase phase = this.phase.get();
            switch (phase) {
                case CLOSED, RESOLVING_RESOLVE_PENDING -> {
                    return;
                }
                // 正在解析: 先把通知记下来, 解析结束时一起处理
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
            }
        }
    }

    /**
     * 进入解析, 这期间到达的通知先记在 phase 上, 结束时一并处理.
     */
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

    /**
     * 结束一次解析.
     *
     * @return 解析期间收到过通知, 需要标记脏槽位时返回 true
     */
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

    /**
     * 返回当前路径状态. Pane 结构变过时先重新解析再返回;
     * Item 变化不用解析, 直接返回现有状态.
     *
     * @return 当前路径状态
     */
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

    /**
     * 确认路径还没关闭.
     *
     * @throws IllegalStateException 路径已关闭时抛出
     */
    private void requireOpen() {
        if (this.phase.get() == Phase.CLOSED) {
            throw new IllegalStateException("displayed path is closed");
        }
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
     * 一次解析的结果: 路径经过的每一层, 终点, 以及沿途算出的背景和冻结状态.
     *
     * <p>它只保存结构和订阅, 不管通知怎么分发 —— 订阅回调都绑在 {@link DisplayedSlotPath} 上,
     * 所以沿用到下一次解析的层照样有效.
     */
    private static final class PathState implements AutoCloseable {
        private Pane[] panes = new Pane[4];  // 从根 Pane 到最深层 Pane
        private int[] paneSlots = new int[4]; // 与 panes 使用相同下标, 记录每层订阅的槽位
        private PaneSlotAttachment[] paneAttachments = new PaneSlotAttachment[4]; // 与 panes 使用相同下标
        private AtomicBoolean[] discardedLayers = new AtomicBoolean[4]; // 与 panes 使用相同下标, 置起来表示这一层已被丢弃
        private int depth;      // 路径当前深度, 即 panes 中已使用的层数
        private int ownedFrom;  // 关闭时从这一层开始取消订阅, 更靠前的层已经交给新路径

        private Element leafElement; // 路径终点的元素, 用来判断终点有没有变

        // Item 部分, 与 Inventory 链接互斥
        private Item item; // 路径终点的 Item
        private ItemAttachment itemAttachment = ItemAttachment.PASSIVE; // 最终的 Item 的 ItemAttachment

        // Inventory 链接部分, 与 Item 互斥
        private Element.InventoryLink inventoryLink; // 路径终点的 Inventory 连接
        private Subscription inventorySubscription;  // Inventory post 事件的渲染订阅
        private Subscription visualSubscription;     // Inventory 视觉映射变更的渲染订阅

        private ItemProvider background;    // 沿路径找到的最深层非 null 的 Pane 背景.
        private boolean frozen;             // 路径上任何 Pane 冻结时都为 true
        // 终点订阅靠它判断自己还算不算数, 见 attachLeaf; 同时保证 close 幂等
        private volatile boolean resourcesClosed;

        /**
         * 沿用旧路径开头若干层的订阅.
         *
         * @param source 上一次解析出的路径, {@code count} 为 0 时允许为 null
         * @param count 沿用的层数
         */
        private void reuse(PathState source, int count) {
            for (int index = 0; index < count; index++) {
                this.add(source.panes[index], source.paneSlots[index], source.paneAttachments[index], source.discardedLayers[index]);
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
         * 记录一层 Pane, 它的槽位及槽位订阅, 必要时扩容数组.
         *
         * @param pane 路径中的 Pane
         * @param paneSlot 该层订阅的 Pane 槽位
         * @param attachment Pane 槽位订阅
         * @param discarded 这一层被丢弃后置起来的标志
         */
        private void add(Pane pane, int paneSlot, PaneSlotAttachment attachment, AtomicBoolean discarded) {
            if (this.depth == this.panes.length) {
                int newLength = this.depth * 2;
                this.panes = Arrays.copyOf(this.panes, newLength);
                this.paneSlots = Arrays.copyOf(this.paneSlots, newLength);
                this.paneAttachments = Arrays.copyOf(this.paneAttachments, newLength);
                this.discardedLayers = Arrays.copyOf(this.discardedLayers, newLength);
            }
            this.panes[this.depth] = pane;
            this.paneSlots[this.depth] = paneSlot;
            this.paneAttachments[this.depth] = attachment;
            this.discardedLayers[this.depth] = discarded;
            this.depth++;
        }

        /**
         * 关闭本路径独有的订阅, 并清掉 Item, 背景和 Pane 引用.
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
            Subscription previousVisualSubscription = this.visualSubscription;
            this.itemAttachment = ItemAttachment.PASSIVE;
            this.inventorySubscription = null;
            this.visualSubscription = null;
            this.item = null;
            this.inventoryLink = null;
            this.leafElement = null;
            this.background = null;

            Throwable failure = ThrowableUtils.captureUnchecked(null, previousItemAttachment::close);

            if (previousInventorySubscription != null) {
                failure = ThrowableUtils.captureUnchecked(failure, previousInventorySubscription::close);
            }

            if (previousVisualSubscription != null) {
                failure = ThrowableUtils.captureUnchecked(failure, previousVisualSubscription::close);
            }

            // 从最深层 Pane 向根 Pane 逆序取消订阅
            for (int index = this.depth - 1; index >= 0; index--) {
                PaneSlotAttachment paneAttachment = this.paneAttachments[index];
                AtomicBoolean discarded = this.discardedLayers[index];
                this.paneAttachments[index] = null;
                this.discardedLayers[index] = null;
                this.panes[index] = null;
                if (index >= this.ownedFrom) {
                    // 先把这一层标成丢弃再取消订阅: 取消订阅和派发通知可能同时发生
                    discarded.set(true);
                    failure = ThrowableUtils.captureUnchecked(failure, paneAttachment::close);
                }
            }
            this.depth = 0;

            ThrowableUtils.throwIfUnchecked(failure);
        }
    }
}
