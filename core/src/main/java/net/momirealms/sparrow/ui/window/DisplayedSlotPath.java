package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.BundleSelect;
import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSlotAttachment;
import net.momirealms.sparrow.ui.gui.SlotElement;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.RefreshPlan;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 记录 Window 中一个槽位当前显示的内容.
 *
 * <p>它从根 GUI 的指定槽位出发, 跟随 {@link SlotElement.GuiLink} 进入子 GUI,
 * 直到找到 Item 或空槽位. GUI 变化会要求重新解析路径, 最终 Item 变化只会要求重新渲染当前路径.</p>
 *
 * <p>路径解析, 显示, 交互和关闭由玩家实体线程执行.
 * 观察通知可以来自其他线程, 但只会标记 Window 槽位, 不会直接修改路径.</p>
 */
final class DisplayedSlotPath implements AutoCloseable {
    private final Window window;
    private final int windowSlot;
    private final Gui rootGui;
    private final int rootSlot;
    private final RenderContext renderContext; // 该 Window 槽位专用的渲染上下文

    private PathState current; // 当前已启用的路径快照
    private volatile boolean closed;

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
            try {
                this.close();
            } catch (RuntimeException | Error closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            throw throwable;
        }
    }

    /**
     * 重新跟随 GUI 链接并替换当前显示路径.
     * <p>新路径全部准备成功后才替换旧路径. 任何订阅失败时, 新路径会被关闭, 旧路径继续工作.
     */
    void resolve() {
        this.requireOpen();
        PathState candidate = new PathState(this.window, this.windowSlot);
        try {
            this.prepare(candidate);
        } catch (RuntimeException | Error throwable) {
            candidate.retire();
            try {
                candidate.close();
            } catch (RuntimeException | Error closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            throw throwable;
        }

        PathState previous = this.current;
        try {
            if (previous != null) {
                previous.retire();
                previous.close();
            }
        } finally {
            this.current = candidate;
            if (candidate.activate()) { // 如果在准备阶段有更新请求过来, 则标记脏位.
                this.window.notifyUpdate(this.windowSlot);
            }
        }
    }

    /**
     * 返回最终 Item 的刷新计划. 空槽位返回不主动刷新的计划.
     *
     * @return Item 刷新计划
     */
    @NotNull RefreshPlan refreshPlan() {
        return this.currentState().itemAttachment.refreshPlan();
    }

    /**
     * 生成该 Window 槽位当前应显示的 ItemStack.
     *
     * <p>有 Item 时显示 Item, 否则显示路径中最深层 GUI 的背景.
     * 两者都没有时返回空物品.</p>
     *
     * @return 要显示的 ItemStack
     */
    @NotNull ItemStack render() {
        PathState state = this.currentState();
        ItemProvider provider = state.item == null
                ? state.background == null ? ItemProvider.EMPTY : state.background
                : state.item.getItemProvider();
        return provider.provide(this.renderContext);
    }

    /**
     * 在路径未冻结且有 Item 时转发点击.
     *
     * @param click 点击上下文
     */
    void handleClick(@NotNull ItemClick click) {
        PathState state = this.currentState();
        if (!state.frozen && state.item != null) {
            state.item.handleClick(click);
        }
    }

    /**
     * 在路径未冻结且有 Item 时转发 Bundle 选择.
     *
     * @param select Bundle 选择上下文
     */
    void handleBundleSelect(@NotNull BundleSelect select) {
        PathState state = this.currentState();
        if (!state.frozen && state.item != null) {
            state.item.handleBundleSelect(select);
        }
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
     * 从根槽位开始跟随 GuiLink, 并订阅沿途的每个 GUI 槽位和最终 Item.
     * <p>遇到空槽位或 Item 时停止. 遇到重复 GUI 时说明链接成环, 立即失败.
     *
     * @param candidate 正在准备的新路径
     */
    private void prepare(PathState candidate) {
        Gui gui = this.rootGui;
        int guiSlot = this.rootSlot;

        while (true) {
            if (candidate.contains(gui)) {
                throw new IllegalStateException("GUI link cycle detected at depth " + candidate.depth + " for local slot " + guiSlot);
            }

            GuiSlotAttachment attachment = gui.attach(guiSlot, _ -> candidate.notifyWindows(true));
            candidate.add(gui, attachment);
            if (attachment.background() != null) {
                candidate.background = attachment.background();
            }
            candidate.frozen |= attachment.frozen();

            switch (attachment.element()) {
                case SlotElement.GuiLink link -> {
                    gui = link.gui();
                    guiSlot = link.slot();
                }
                case SlotElement.Item(var item) -> {
                    candidate.item = item;
                    candidate.itemAttachment = item.attach(_ -> candidate.notifyWindows(false));
                    return;
                }
                case SlotElement.Empty _ -> {
                    return;
                }
            }
        }
    }

    /**
     * 返回当前已解析路径.
     * GUI 变化后会先重建路径,
     * Item 变化则继续使用原有挂载状态.
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
            PREPARING, // 正在建立订阅, 尚未成为当前路径
            PREPARING_RENDER_PENDING, // 准备期间收到渲染通知, 启用后标记脏槽位
            PREPARING_RESOLVE_PENDING, // 准备期间收到结构通知, 启用后需要重建路径
            ACTIVE, // 当前正在显示的路径
            ACTIVE_RESOLVE_REQUIRED, // 当前路径收到结构通知, 下次读取前重建
            RETIRED // 已被替换, 忽略迟到的通知
        }

        private final Window window;
        private final int windowSlot;
        private final AtomicReference<GateState> gate = new AtomicReference<>(GateState.PREPARING);

        private Gui[] guis = new Gui[4]; // 从根 GUI 到最深层 GUI
        private GuiSlotAttachment[] guiAttachments = new GuiSlotAttachment[4]; // 与 guis 使用相同下标
        private int depth;

        private Item item;
        private ItemProvider background; // 沿路径找到的最深层非 null 背景
        private boolean frozen; // 路径上任何 GUI 冻结时都为 true
        private ItemAttachment itemAttachment = ItemAttachment.passive(); // 最终的 Item 的 ItemAttachment
        private boolean resourcesClosed;

        private PathState(Window window, int windowSlot) {
            this.window = window;
            this.windowSlot = windowSlot;
        }

        /**
         * 处理一次失效通知. 可能由任意线程调用, 只更新标志位和脏标记,
         * 实际的重建由实体线程在下一次读取路径时执行.
         *
         * @param resolveRequired 通知是否来自 GUI, 要求重建整条路径;
         *                        false 表示只来自最终 Item, 重新渲染即可
         */
        private void notifyWindows(boolean resolveRequired) {
            while (true) {
                GateState state = this.gate.get();
                switch (state) {
                    case PREPARING -> {
                        GateState updated = resolveRequired
                                ? GateState.PREPARING_RESOLVE_PENDING
                                : GateState.PREPARING_RENDER_PENDING;
                        if (this.gate.compareAndSet(state, updated)) {
                            return;
                        }
                    }
                    case PREPARING_RENDER_PENDING -> {
                        if (!resolveRequired) {
                            return;
                        }
                        if (this.gate.compareAndSet(state, GateState.PREPARING_RESOLVE_PENDING)) {
                            return;
                        }
                    }
                    case PREPARING_RESOLVE_PENDING, RETIRED -> {
                        return;
                    }
                    case ACTIVE -> {
                        if (resolveRequired
                                && !this.gate.compareAndSet(state, GateState.ACTIVE_RESOLVE_REQUIRED)) {
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
         * 将候选路径变为当前路径, 并返回准备期间是否收到过更新.
         *
         * @return 准备期间收到过更新时为 true
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
                if (this.gate.compareAndSet(state, activated)) {
                    return pending;
                }
            }
        }

        /**
         * 返回 GUI 更新是否要求当前路径重新解析.
         *
         * @return 需要重新解析时为 true
         */
        private boolean requiresResolve() {
            return this.gate.get() == GateState.ACTIVE_RESOLVE_REQUIRED;
        }

        /**
         * 把路径标记为已退役, 以后到达的通知将被忽略.
         */
        private void retire() {
            this.gate.getAndSet(GateState.RETIRED);
        }

        /**
         * 尝试关闭路径上的每个订阅, 并清除 Item, 背景和 GUI 引用.
         *
         * <p>某个订阅关闭失败时仍会继续关闭其余订阅, 最后再抛出收集到的异常.</p>
         */
        @Override
        public void close() {
            if (this.resourcesClosed) {
                return;
            }
            this.resourcesClosed = true;

            // 先断开自身持有的状态引用, 再调用外部 close
            ItemAttachment previousItemAttachment = this.itemAttachment;
            this.itemAttachment = ItemAttachment.passive();
            this.item = null;
            this.background = null;

            Throwable failure = null;
            try {
                previousItemAttachment.close();
            } catch (RuntimeException | Error throwable) {
                failure = throwable;
            }

            // 从最深层 GUI 向根 GUI 逆序取消订阅
            for (int index = this.depth - 1; index >= 0; index--) {
                GuiSlotAttachment guiAttachment = this.guiAttachments[index];
                this.guiAttachments[index] = null;
                this.guis[index] = null;
                try {
                    guiAttachment.close();
                } catch (RuntimeException | Error throwable) {
                    if (failure == null) {
                        failure = throwable;
                    } else {
                        failure.addSuppressed(throwable);
                    }
                }
            }
            this.depth = 0;

            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
