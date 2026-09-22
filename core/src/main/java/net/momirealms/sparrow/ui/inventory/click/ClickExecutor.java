package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.transaction.InteractionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.InventoryTransactions;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

// 一次点击算出候选之后, 先把 Bukkit 事件发出去, 再发 Sparrow 自己的点击事件, 最后才提交.
// 这两轮事件里跑的都是别人写的代码, 它们可以取消, 也可以顺手改掉光标或者槽位内容.
// 所以每轮跑完都要回头看一眼现场还是不是规划时那个样子; 变了就按新现场重算一次候选, 只给这一次机会.
final class ClickExecutor {
    private final ClickSemantics.Context context;
    private final ClickSemantics.InteractionGate gate;
    private final InteractionOverlay overlay;
    private final Supplier<@Nullable ClickCandidate> replan; // 监听器改过现场之后, 拿新现场再算一遍候选
    private final Supplier<String> describe;                 // 候选被扔掉时用它拼告警, 插件作者至少知道是哪一次点击没生效

    private ClickExecutor(
            ClickSemantics.Context context,
            ClickSemantics.InteractionGate gate,
            InteractionOverlay overlay,
            Supplier<@Nullable ClickCandidate> replan,
            Supplier<String> describe
    ) {
        this.context = context;
        this.gate = gate;
        this.overlay = overlay;
        this.replan = replan;
        this.describe = describe;
    }

    // 单击的主流程. 先算出这一下究竟要改哪些槽位, 然后走两轮事件, 最后提交.
    // 只要这一格归点击语义管, Bukkit 事件就一定发, 哪怕这次什么都改不动, 有些插件就靠这个事件拦东西.
    // 唯一的例外是冻结槽, 点它连事件都不发, 只把客户端猜错的画面纠回来.
    static boolean handleClick(
            @NotNull ClickSemantics.Context context,
            @NotNull ClickType clickType,
            int hotbarButton,
            int windowSlot,
            @Nullable ItemStack observedBundle,
            int selectedIndex,
            @NotNull Runnable afterCommit,
            @NotNull ClickSemantics.InteractionGate gate
    ) {
        // 第一次规划和事件之后的重算用的是同一批参数, 所以包成一个供给器给两边共用.
        // 第一次跑的时候覆盖层还是空的, 读到的就是 Inventory 自己那份规划基准.
        InteractionOverlay overlay = InteractionOverlay.forClick();
        Supplier<ClickPlanner.PreparedClick> plan = () -> ClickPlanner.prepareClick(
                context,
                clickType,
                hotbarButton,
                windowSlot,
                observedBundle,
                selectedIndex,
                afterCommit,
                true,
                overlay
        );
        ClickPlanner.PreparedClick prepared = plan.get();
        ClickCandidate candidate = prepared.candidate();
        ClickExecutor executor = new ClickExecutor(
                context,
                gate,
                overlay,
                () -> plan.get().candidate(),
                () -> "点击 " + clickType + " @ windowSlot " + windowSlot
        );
        if (candidate != null) {
            executor.executeCandidate(candidate, edits -> gate.allowClick(candidate.action(), edits));
        } else if (prepared.handled() && !context.frozenAt(windowSlot) && !executor.inventoryFrozenAt(windowSlot)) {
            // 这一下本身改不动任何东西, 但监听器还是有机会自己往里写点什么, 那些写入照样要落地.
            executor.executeUnplanned(clickType, hotbarButton, windowSlot, prepared.action());
        }
        if (prepared.handled() && windowSlot != InventoryView.OUTSIDE) {
            context.markDirty(windowSlot);
        }
        return prepared.handled();
    }

    // 拖拽也是先算好每一格分到多少, 再发事件. 这样 Bukkit 事件里的 newItems 和随后提交的东西是同一份.
    static void handleDrag(
            @NotNull ClickSemantics.Context context,
            @NotNull ClickType clickType,
            @NotNull List<Integer> windowSlots,
            @NotNull ClickSemantics.InteractionGate gate
    ) {
        // 分配在发事件之前就定下来了, 所以监听器往光标上写的是最终值, 不像单击那样还要当输入再读一遍.
        InteractionOverlay overlay = InteractionOverlay.forDrag();
        DragPlanner.PreparedDrag prepared = DragPlanner.prepare(context, clickType, windowSlots, overlay);
        if (prepared != null) {
            ClickExecutor executor = new ClickExecutor(
                    context,
                    gate,
                    overlay,
                    () -> {
                        // 重算之后每格分到多少可能和 Bukkit 事件里那份 newItems 不一样了.
                        // 这里不补发第二次事件, 一趟拖拽只算一次派发.
                        DragPlanner.PreparedDrag replanned = DragPlanner.prepare(context, clickType, windowSlots, overlay);
                        return replanned == null ? null : replanned.candidate();
                    },
                    () -> "拖拽 " + clickType + " @ windowSlots " + windowSlots
            );
            executor.executeCandidate(prepared.candidate(), edits -> gate.allowDrag(prepared.newCursor().clone(), prepared.newItems(), edits));
        }
        markAllDirty(context, windowSlots);
    }

    // 点在窗口外面. 原版只给左右边框定义了丢东西的语义, 别的类型什么都不做,
    // 比如创造模式在窗外按中键会送来一个 CLONE, 那个就直接忽略.
    static void handleOutsideClick(
            @NotNull ClickSemantics.Context context,
            @NotNull ClickType clickType
    ) {
        ItemStack cursor = context.cursor();
        if (ItemUtils.isEmpty(cursor)) {
            return;
        }
        if (clickType == ClickType.WINDOW_BORDER_LEFT) {
            context.cursor(ItemUtils.EMPTY);
            context.drop(cursor.clone());
        } else if (clickType == ClickType.WINDOW_BORDER_RIGHT) {
            int left = cursor.getAmount() - 1;
            context.cursor(left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemUtils.EMPTY);
            context.drop(ItemUtils.copyWithAmount(cursor, 1));
        }
    }

    // Bukkit 监听器跑完之后复核一遍候选. 现场被改过就重算一次, 拿新候选继续往 Sparrow 事件那一步走.
    private void executeCandidate(ClickCandidate candidate, Predicate<InteractionEdits> bukkitStage) {
        if (candidate.staleReason(this.context) != null) {
            return;
        }
        boolean fireBukkitInventoryEvent = requestsBukkitInventoryEvent(candidate.eventTarget(), candidate.scopes());
        InteractionEdits edits = this.editsFor(candidate, this.overlay);
        if (!this.passGate(() -> !fireBukkitInventoryEvent || bukkitStage.test(edits))) {
            return;
        }
        // 只有 Bukkit 监听器的写入会攒进覆盖层当输入. 这之后无论谁再写, 写的都是提交后的最终值, 所以这里就把覆盖层收了.
        edits.closeOverlay();
        @Nullable ClickCandidate.StaleReason stale = this.recheck(candidate, fireBukkitInventoryEvent && this.gate.firesBukkitEvents(), edits);
        if (stale == null && this.overlay.isEmpty()) {
            this.finishCandidate(candidate, edits);
            return;
        }
        @Nullable ClickCandidate replanned = this.replan.get();
        if (replanned == null) {
            // 新现场下这一点已经算不出候选了. 监听器自己写进来的那部分不能跟着一起丢, 单独当一笔事务提交.
            InteractionEdits settled = this.settled(null);
            ClickSemantics.LinkedSlot eventTarget = candidate.eventTarget();
            if (eventTarget != null
                    && !this.passGate(() -> this.gate.allowInventoryClick(eventTarget, InventoryAction.NOTHING, settled))) {
                return;
            }
            this.commitEdits(candidate.reason(), settled);
            return;
        }
        if (replanned.staleReason(this.context) != null) {
            return;
        }
        this.finishCandidate(replanned, this.settled(replanned));
    }

    // 发 Sparrow 自己的点击事件, 然后提交. 重算之后也是从这里接着跑, 所以这一段里一个 Bukkit 事件都没有.
    private void finishCandidate(ClickCandidate candidate, InteractionEdits edits) {
        ClickSemantics.LinkedSlot eventTarget = candidate.eventTarget();
        if (eventTarget != null) {
            if (!this.gate.stillValid()) {
                return;
            }
            boolean observed = eventTarget.inventory().hasClickObservers();
            if (
                    !this.gate.allowInventoryClick(eventTarget, candidate.action(), edits)
                    || !this.gate.stillValid()
                    || !this.survived(this.recheck(candidate, observed, edits))
            ) {
                return;
            }
        }
        @Nullable TransactionDraft draft = edits.transaction();
        if (draft == null) {
            if (this.survived(stale(candidate.staleReason(this.context), edits)) && this.gate.stillValid()) {
                candidate.draft().seal();
                candidate.applyAfterCommit(this.context);
            }
            return;
        }
        InventoryTransactions.commit(
                candidate.reason(),
                draft,
                candidate.draft(),
                false,
                () -> candidate.applyAfterCommit(this.context),
                candidate.plannedRoots(),
                () -> this.survived(stale(candidate.staleReason(this.context), edits)) && this.gate.stillValid()
        );
    }

    // 这一下算不出候选, 但事件还是得发. 草稿等监听器真写了东西再建, 最后也只提交它们写的那些.
    private void executeUnplanned(ClickType clickType, int hotbarButton, int windowSlot, InventoryAction action) {
        InteractionEdits edits = new InteractionEdits(this.context, null, null, this.overlay);
        ItemStack plannedCursor = this.context.cursor();
        boolean fireBukkitInventoryEvent = requestsBukkitInventoryEvent(this.context.linkAt(windowSlot), List.of());
        if (!this.passGate(() -> !fireBukkitInventoryEvent || this.gate.allowClick(action, edits))) {
            return;
        }
        edits.closeOverlay();
        // 刚才算不出候选, 是因为现场不合适. 监听器动过现场之后条件可能凑齐了, 这里再给一次机会.
        if (!this.overlay.isEmpty() || !ItemUtils.isHandleContentEqual(this.context.unsafeCursor(), plannedCursor)) {
            @Nullable ClickCandidate replanned = this.replan.get();
            if (replanned != null && replanned.staleReason(this.context) == null) {
                // 重算出来的候选自带事件目标, Sparrow 点击事件就照它发.
                this.finishCandidate(replanned, this.settled(replanned));
                return;
            }
        }
        // 结算必须排在 Sparrow 事件前面. 这样事件读到的是 Bukkit 监听器留下的结果, 它自己写的又能接着进提交.
        InteractionEdits settled = this.settled(null);
        @Nullable ClickSemantics.LinkedSlot link = this.context.linkAt(windowSlot);
        if (link != null && !this.passGate(() -> this.gate.allowInventoryClick(link, action, settled))) {
            return;
        }
        this.commitEdits(
                new PlayerUpdateReason.Click(this.context.viewer(), clickType, clickType == ClickType.NUMBER_KEY ? hotbarButton : -1),
                settled
        );
    }

    // 提交纯粹来自监听器的那些写入. 没有候选可以对照, 能复核的只有监听器当时看到的光标和 Window 还开着没有.
    private void commitEdits(UpdateReason reason, InteractionEdits edits) {
        @Nullable InteractionDraft interaction = edits.interaction();
        @Nullable TransactionDraft draft = edits.transaction();
        if (draft == null) {
            if (interaction != null && this.survived(edits.staleCursor()) && this.gate.stillValid()) {
                interaction.seal();
                interaction.apply(this.context);
            }
            return;
        }
        InventoryTransactions.commit(
                reason,
                draft,
                interaction,
                false,
                interaction == null ? null : () -> interaction.apply(this.context),
                List.of(),
                () -> this.survived(edits.staleCursor()) && this.gate.stillValid()
        );
    }

    // 中间真跑过别人的代码, 就得重新同步一次外部容器再比对, 期间它可能被直接改掉了.
    // 一路放行没人插手的话, 拿手上这份基准比一比就够, 不值得再读一遍容器.
    @Nullable
    private ClickCandidate.StaleReason recheck(ClickCandidate candidate, boolean userCodeRan, InteractionEdits edits) {
        return stale(userCodeRan ? candidate.revalidate(this.context) : candidate.staleReason(this.context), edits);
    }

    @Nullable
    private static ClickCandidate.StaleReason stale(@Nullable ClickCandidate.StaleReason reason, InteractionEdits edits) {
        return reason != null ? reason : edits.staleCursor();
    }

    // 写集不空就先建草稿, 形状校验在这一刻完成, 别等别人的代码跑起来才发现写集本身就是坏的.
    @NotNull
    private InteractionEdits editsFor(ClickCandidate candidate, @Nullable InteractionOverlay overlay) {
        @Nullable TransactionDraft planned = candidate.scopes().isEmpty()
                ? null
                : new TransactionDraft(candidate.scopes());
        return new InteractionEdits(this.context, planned, candidate.draft(), overlay);
    }

    // 把覆盖层里攒的东西结算成最终值. 旧候选已经作废, 它那份光标和掉落物草稿不能跟着带进重算出来的新候选.
    @NotNull
    private InteractionEdits settled(@Nullable ClickCandidate replanned) {
        InteractionEdits target = replanned == null
                ? new InteractionEdits(this.context, null, null, null)
                : this.editsFor(replanned, null);
        target.settle(this.overlay, replanned);
        return target;
    }

    // 发事件前后各看一次 Window 还是不是原来那扇. 处理器里关窗口、开新窗口都是常见写法.
    private boolean passGate(BooleanSupplier stage) {
        return this.gate.stillValid() && stage.getAsBoolean() && this.gate.stillValid();
    }

    // 这一笔牵扯到的 Inventory 里只要有一个愿意派发 Bukkit 事件, 这次就发.
    // 宁可多发也不少发, 漏发会让靠 Bukkit 事件做限制的插件失效.
    private static boolean requestsBukkitInventoryEvent(@Nullable ClickSemantics.LinkedSlot directTarget, @NotNull List<TransactionScope> scopes) {
        if (directTarget != null && directTarget.inventory().fireBukkitInventoryEvents()) {
            return true;
        }
        for (int scopeIndex = 0; scopeIndex < scopes.size(); scopeIndex++) {
            if (scopes.get(scopeIndex).inventory().fireBukkitInventoryEvents()) {
                return true;
            }
        }
        return false;
    }

    // 候选还算不算数. 不算数就整笔扔掉, 光标那种情况额外喊一声.
    // 手上这份候选是按改动之前那份光标算的, 提交下去等于把人家刚写的值盖掉, 与其静静地盖不如报出来.
    private boolean survived(@Nullable ClickCandidate.StaleReason reason) {
        if (reason == null) {
            return true;
        }
        if (reason == ClickCandidate.StaleReason.CURSOR) {
            SparrowUI.getInstance().warn(this.describe.get() + " 被丢弃: 提交之前直接改掉了菜单实际光标"
                    + "(如 HumanEntity#setItemOnCursor), 本次结论依据的是改动之前那份光标, 提交它会盖掉这次改动."
                    + " 请改用交互写入句柄的 cursor(...).");
        }
        return false;
    }

    // 玩家侧只读的 Inventory 按冻结槽那套待遇走, 点它连一个空操作事件都不发, 只把客户端猜错的画面纠回来.
    private boolean inventoryFrozenAt(int windowSlot) {
        ClickSemantics.LinkedSlot link = this.context.linkAt(windowSlot);
        return link != null && link.inventory().frozen();
    }

    private static void markAllDirty(ClickSemantics.Context context, List<Integer> windowSlots) {
        for (int windowIndex = 0; windowIndex < windowSlots.size(); windowIndex++) {
            context.markDirty(windowSlots.get(windowIndex));
        }
    }
}
