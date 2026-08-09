package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.click.BundleSelectClick;
import net.momirealms.sparrow.ui.click.ItemClick;
import net.momirealms.sparrow.ui.click.ItemDragClick;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSlotAttachment;
import net.momirealms.sparrow.ui.gui.SlotElement;
import net.momirealms.sparrow.ui.inventory.ClickSemantics;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.RefreshPlan;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 记录 Window 中一个槽位当前显示的内容.
 *
 * <p>它从根 GUI 的指定槽位出发, 跟随 {@link SlotElement.GuiLink} 一层层进入子 GUI,
 * 直到找到 Item, Inventory连接或空槽位. GUI 变了要重新解析整条路径; 最终 Item 变了或
 * Inventory 有事务通知, 只需要重新渲染一次.
 *
 * <p>路径解析, 显示, 交互和关闭都在玩家实体线程执行.
 * 观察通知可以来自其他线程, 但只会给 Window 槽位打脏标记, 不会直接动路径.
 */
final class DisplayedSlotPath implements AutoCloseable {
    private final Window window;    // 所属 Window
    private final int windowSlot;   // 本路径服务的 Window 槽位
    private final Gui rootGui;      // 路径起点的根 GUI
    private final int rootSlot;     // 路径起点在根 GUI 中的槽位
    private final RenderContext renderContext;  // 该 Window 槽位专用的渲染上下文

    private PathState current;          // 当前已启用的路径快照
    private volatile boolean closed;    // 路径是否已关闭, 关闭后迟到的通知直接忽略

    /**
     * 创建并立即解析一个 Window 槽位的显示路径.
     *
     * @param window 所属 Window
     * @param windowSlot Window 槽位编号
     * @param rootGui 显示路径的根 GUI
     * @param rootSlot 根 GUI 槽位编号
     */
    DisplayedSlotPath(@NotNull Window window, int windowSlot, @NotNull Gui rootGui, int rootSlot) {
        this.window = window;
        this.windowSlot = windowSlot;
        this.rootGui = rootGui;
        this.rootSlot = rootGui.size().checkSlot(rootSlot);
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
     * 重新跟随 GUI 链接, 建一条新的显示路径替换当前的.
     * <p>新路径全部准备成功后才替换; 中途任何订阅失败, 新路径直接关掉, 旧路径继续工作.
     */
    void resolve() {
        this.requireOpen();
        PathState candidate = new PathState(this.window, this.windowSlot);
        try {
            this.prepare(candidate);
        } catch (RuntimeException | Error throwable) {
            // 准备失败: 关掉候选已建立的部分, 旧路径不受影响
            candidate.retire();
            ThrowableUtils.captureUnchecked(throwable, candidate::close);
            throw throwable;
        }

        // 准备成功: 退役旧路径, 候选转正; 准备期间攒下的更新补一个脏标记
        PathState previous = this.current;
        boolean interactionChanged = previous != null
                && (previous.frozen != candidate.frozen || !Objects.equals(previous.inventoryLink, candidate.inventoryLink));
        try {
            if (previous != null) {
                previous.retire();
                previous.close();
            }
        } finally {
            this.current = candidate;
            // todo 这里是不是有点牵强, 我的意思是调用方法.
            if (interactionChanged && this.window instanceof AbstractWindow<?> abstractWindow) {
                abstractWindow.notifyInteractionPathChanged();
            }
            if (candidate.activate()) { // 如果在准备阶段有更新请求过来, 则标记脏位.
                this.window.notifyUpdate(this.windowSlot);
            }
        }
    }

    /**
     * 返回最终 Item 的刷新计划.
     * 空槽位返回不主动刷新的计划.
     *
     * @return Item 刷新计划
     */
    @NotNull RefreshPlan refreshPlan() {
        return this.currentState().itemAttachment.refreshPlan();
    }

    /**
     * 生成当前槽位应显示的 ItemStack, 按以下优先级查找:
     * <ol>
     *   <li>若路径终点为 InventoryLink, 优先显示 Inventory 的槽位映射,
     *       没有映射就显示内容, 最后显示 Inventory 的背景. 没有背景就保持空槽.</li>
     *   <li>若路径终点为 Item, 显示该 Item</li>
     *   <li>若路径终点为 Empty, 回退为最深层 GUI 的背景</li>
     *   <li>若仍无结果, 返回空物品作为最终兜底</li>
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
            ItemStack stack = inventory.itemAt(slot);
            ItemProvider visual = inventory.visualize(slot, stack);
            if (visual != null) {
                return visual.provide(this.renderContext);
            }
            if (stack != null) {
                return stack;
            }
            return ItemStack.empty();
        }

        ItemProvider provider = state.item == null
                ? state.background == null ? ItemProvider.EMPTY : state.background
                : state.item.getItemProvider();
        return provider.provide(this.renderContext);
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
    SlotElement.InventoryLink inventoryLink() {
        return this.currentState().inventoryLink;
    }

    /**
     * 返回路径是否按冻结处理: 经过已冻结 GUI, 或槽位被 Window 冻结;
     * 冻结槽不参与点击语义与 Item 分派.
     *
     * @return 路径按冻结处理时返回 true
     */
    boolean frozen() {
        return this.windowFrozen() || this.currentState().frozen;
    }

    // Window 侧的单槽冻结与沿途 GUI 冻结同待遇, 任一生效本路径即按冻结处理.
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

    // 强制处理尚未解析的 GUI 变化, 让 Window 在提交旧候选前看到交互终点或冻结状态的改变.
    void refreshInteractionState() {
        this.currentState();
    }

    /**
     * 返回路径是否已关闭.
     *
     * @return 已关闭时为 true
     */
    boolean isClosed() {
        return this.closed;
    }

    /**
     * 关闭当前路径并取消所有 GUI 和 Item 订阅.
     * 重复调用安全.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        PathState previous = this.current;
        this.current = null;
        if (previous != null) {
            previous.retire();
            previous.close();
        }
    }

    /**
     * 从根 GUI 槽位开始一层层跟随 GuiLink, 并订阅沿途的每个 GUI 槽位和最终 Item.
     * <p>遇到空槽位或 Item 就停. 遇到重复的 GUI 说明链接成环, 直接失败.
     *
     * @param candidate 正在准备的新路径
     */
    private void prepare(PathState candidate) {
        Gui gui = this.rootGui;
        int guiSlot = this.rootSlot;

        while (true) {
            // 链接成环直接失败
            if (candidate.contains(gui)) {
                throw new IllegalStateException("GUI link cycle detected at depth " + candidate.depth + " for local slot " + guiSlot);
            }

            // 订阅这一层 GUI 的槽位: GUI 的失效通知会要求重建路径
            GuiSlotAttachment attachment = gui.attach(guiSlot, ignoredInvalidation -> candidate.notifyWindows(true));
            candidate.add(gui, attachment);
            // 记录沿途最深层背景; 任何一层 GUI 冻结, 整条路径都视为经过已冻结 GUI
            if (attachment.background() != null) {
                candidate.background = attachment.background();
            }
            candidate.frozen |= attachment.frozen();

            // 按槽位元素决定走向: GuiLink 继续深入, 其余三种都是终点
            switch (attachment.element()) {
                case SlotElement.GuiLink link -> {
                    gui = link.gui();
                    guiSlot = link.slot();
                }
                case SlotElement.Item(var item) -> {
                    candidate.item = item;
                    candidate.itemAttachment = item.attach(ignoredInvalidation -> candidate.notifyWindows(false));
                    return;
                }
                case SlotElement.InventoryLink link -> {
                    candidate.inventoryLink = link;
                    candidate.inventorySubscription = link.inventory().subscribePostUpdate(event -> {
                        // 事件使用当前订阅 Inventory 的槽位坐标, 只需检查当前路径连接的槽号.
                        for (int i = 0; i < event.slotChanges().size(); i++) {
                            if (event.slotChanges().get(i).slot() == link.slot()) {
                                candidate.notifyWindows(false);
                                return;
                            }
                        }
                    });
                    candidate.visualSubscription = link.inventory().subscribeVisualInvalidation(slot -> {
                        if (slot == SparrowInventory.ALL_SLOTS || slot == link.slot()) {
                            candidate.notifyWindows(false);
                        }
                    });
                    return;
                }
                case SlotElement.Empty ignoredEmpty -> {
                    return;
                }
            }
        }
    }

    /**
     * 返回当前路径状态. GUI 结构变过时先重建路径再返回;
     * Item 变化不用重建, 直接返回现有状态.
     *
     * @return 当前路径状态
     */
    @NotNull
    private PathState currentState() {
        this.requireOpen();
        if (this.current == null) {
            throw new IllegalStateException("displayed path has not been resolved");
        }
        if (this.current.requiresResolve()) {
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
        if (this.closed) {
            throw new IllegalStateException("displayed path is closed");
        }
    }

    /**
     * 保存一次已解析路径的状态和全部订阅.
     * <p>沿途 GUI 和最终 Item 通过各自的失效回调通知此路径:
     * GUI 更新要求重建路径, Item 更新只要求重新渲染, 不会重建挂载.
     * 回调绑定在路径实例上, 因此旧路径退役后延迟到达的通知会被忽略.
     */
    private static final class PathState implements AutoCloseable {
        /**
         * 保存路径的生命周期, 以及尚未处理的刷新要求.
         */
        private enum GateState {
            PREPARING,                  // 正在建立订阅, 尚未成为当前路径
            PREPARING_RENDER_PENDING,   // 准备期间收到渲染通知, 启用后标记脏槽位
            PREPARING_RESOLVE_PENDING,  // 准备期间收到结构通知, 启用后需要重建路径
            ACTIVE,                     // 当前正在显示的路径
            ACTIVE_RESOLVE_REQUIRED,    // 当前路径收到结构通知, 下次读取前重建
            RETIRED                     // 已被替换, 忽略迟到的通知
        }

        private final Window window;    // 所属 Window, 用于标记脏槽位
        private final int windowSlot;   // 本路径服务的 Window 槽位
        private final AtomicReference<GateState> gate = new AtomicReference<>(GateState.PREPARING); // 生命周期与待处理通知的门闩, 用 CAS 更新

        private Gui[] guis = new Gui[4]; // 从根 GUI 到最深层 GUI
        private GuiSlotAttachment[] guiAttachments = new GuiSlotAttachment[4]; // 与 guis 使用相同下标
        private int depth;               // 路径当前深度, 即 guis 中已使用的层数

        // Item 部分, 与 Inventory 链接互斥
        private Item item; // 路径终点的 Item
        private ItemAttachment itemAttachment = ItemAttachment.PASSIVE; // 最终的 Item 的 ItemAttachment

        // Inventory 链接部分, 与 Item 互斥
        private SlotElement.InventoryLink inventoryLink; // 路径终点的 Inventory 连接
        private Subscription inventorySubscription;      // Inventory post 事件的渲染订阅
        private Subscription visualSubscription;         // Inventory 视觉映射变更的渲染订阅

        private ItemProvider background;    // 沿路径找到的最深层非 null 的 GUI 背景.
        private boolean frozen;             // 路径上任何 GUI 冻结时都为 true
        private boolean resourcesClosed;    // 订阅是否已全部关闭, 保证 close 幂等

        /**
         * 创建一条还在准备中的候选路径.
         *
         * @param window 所属 Window
         * @param windowSlot 本路径服务的 Window 槽位
         */
        private PathState(Window window, int windowSlot) {
            this.window = window;
            this.windowSlot = windowSlot;
        }

        /**
         * 处理一次失效通知. 任意线程都可能调用, 所以这里只改标志位和脏标记,
         * 真正的重建由实体线程下次读取路径时再做.
         *
         * @param resolveRequired true 表示通知来自 GUI, 要重建整条路径;
         *                        false 表示只来自最终 Item, 重新渲染就够了
         */
        private void notifyWindows(boolean resolveRequired) {
            while (true) {
                GateState state = this.gate.get();
                switch (state) {
                    // 准备期间收到通知: 先记下来, 启用时一起处理
                    case PREPARING -> {
                        GateState updated = resolveRequired
                                ? GateState.PREPARING_RESOLVE_PENDING
                                : GateState.PREPARING_RENDER_PENDING;
                        if (this.gate.compareAndSet(state, updated)) {
                            return;
                        }
                    }
                    // 渲染通知已记录; 结构通知要升级成重建标记
                    case PREPARING_RENDER_PENDING -> {
                        if (!resolveRequired) {
                            return;
                        }
                        if (this.gate.compareAndSet(state, GateState.PREPARING_RESOLVE_PENDING)) {
                            return;
                        }
                    }
                    // 重建已记录或路径已注销: 不需要再做任何事
                    case PREPARING_RESOLVE_PENDING, RETIRED -> {
                        return;
                    }
                    // 当前路径收到结构通知: 标记下次读取前重建; 两种通知都要标脏
                    case ACTIVE -> {
                        if (resolveRequired && !this.gate.compareAndSet(state, GateState.ACTIVE_RESOLVE_REQUIRED)) {
                            continue;
                        }
                        this.window.notifyUpdate(this.windowSlot);
                        return;
                    }
                    // 重建已标记, 补一个脏标记即可
                    case ACTIVE_RESOLVE_REQUIRED -> {
                        this.window.notifyUpdate(this.windowSlot);
                        return;
                    }
                }
            }
        }

        /**
         * 检查 GUI 是否已经出现在当前路径中, 用于拒绝循环链接.
         *
         * @param gui 要检查的 GUI
         * @return 已经出现时为 true
         */
        private boolean contains(Gui gui) {
            for (int index = 0; index < this.depth; index++) {
                if (this.guis[index] == gui) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 记录一层 GUI 及其槽位订阅, 必要时扩容数组.
         *
         * @param gui 路径中的 GUI
         * @param attachment GUI 槽位订阅
         */
        private void add(Gui gui, GuiSlotAttachment attachment) {
            if (this.depth == this.guis.length) {
                int newLength = this.depth * 2;
                this.guis = Arrays.copyOf(this.guis, newLength);
                this.guiAttachments = Arrays.copyOf(this.guiAttachments, newLength);
            }
            this.guis[this.depth] = gui;
            this.guiAttachments[this.depth] = attachment;
            this.depth++;
        }

        /**
         * 把候选路径转正成当前路径.
         *
         * @return 准备期间收到过更新时返回 true
         */
        private boolean activate() {
            while (true) {
                GateState state = this.gate.get();
                GateState activated;
                boolean pending;
                switch (state) {
                    case PREPARING -> {
                        activated = GateState.ACTIVE;
                        pending = false;
                    }
                    case PREPARING_RENDER_PENDING -> {
                        activated = GateState.ACTIVE;
                        pending = true;
                    }
                    case PREPARING_RESOLVE_PENDING -> {
                        activated = GateState.ACTIVE_RESOLVE_REQUIRED;
                        pending = true;
                    }
                    default -> throw new IllegalStateException("only a preparing path can be activated");
                }
                // 比较一下在进行转正期间, 有没有其他线程发起修改 gate, 如果有则需要重新解析.
                if (this.gate.compareAndSet(state, activated)) {
                    return pending;
                }
            }
        }

        /**
         * 返回 GUI 结构变化是否要求这条路径重新解析.
         *
         * @return 需要重新解析时返回 true
         */
        private boolean requiresResolve() {
            return this.gate.get() == GateState.ACTIVE_RESOLVE_REQUIRED;
        }

        /**
         * 把路径标记为已注销, 之后迟到的通知直接忽略.
         */
        private void retire() {
            this.gate.getAndSet(GateState.RETIRED);
        }

        /**
         * 关闭路径上的所有订阅, 并清掉 Item, 背景和 GUI 引用.
         * <p>某个订阅关闭失败也会继续关其余的, 最后再把收集到的异常抛出来. 重复调用安全.
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
            this.background = null;

            Throwable failure = ThrowableUtils.captureUnchecked(null, previousItemAttachment::close);

            if (previousInventorySubscription != null) {
                failure = ThrowableUtils.captureUnchecked(failure, previousInventorySubscription::close);
            }

            if (previousVisualSubscription != null) {
                failure = ThrowableUtils.captureUnchecked(failure, previousVisualSubscription::close);
            }

            // 从最深层 GUI 向根 GUI 逆序取消订阅
            for (int index = this.depth - 1; index >= 0; index--) {
                GuiSlotAttachment guiAttachment = this.guiAttachments[index];
                this.guiAttachments[index] = null;
                this.guis[index] = null;
                failure = ThrowableUtils.captureUnchecked(failure, guiAttachment::close);
            }
            this.depth = 0;

            ThrowableUtils.throwIfUnchecked(failure);
        }
    }
}
