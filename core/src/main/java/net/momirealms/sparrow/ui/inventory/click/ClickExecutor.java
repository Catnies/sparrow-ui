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

// 点击语义的执行端, 拿规划器(单击 ClickPlanner, 拖拽 DragPlanner)算好的候选, 依次过闸门, 最后提交事务.
// 每次交互建一个实例, 全程共用的现场(Context, 闸门, 覆盖层, 重规划与交互描述)收成字段, 各阶段方法只传自己那一段特有的参数.
// 候选在每道闸门前后都重新校验. Bukkit 监听器在那里写下的槽位与光标先攒进覆盖层, 与结论进同一笔事务.
// 重规划至多一次, 也不会重新派发 Bukkit 事件, 其余位置校验失败一律整体放弃.
final class ClickExecutor {
    private final ClickSemantics.Context context;
    private final ClickSemantics.InteractionGate gate;
    private final InteractionOverlay overlay;
    private final Supplier<@Nullable ClickCandidate> replan; // 闸门之后按新现场重算一次候选
    private final Supplier<String> describe;                 // 候选被静默丢弃时给插件作者留线索的交互描述

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

    // 先形成精确候选, 再依次经过 Bukkit 和 Sparrow 点击事件, 最后提交候选事务.
    // 引擎接管的槽位一律派发 Bukkit 点击事件, 即使这次点击算不出候选; 只有冻结槽完全不派发.
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
        // 首次规划与闸门之后的重规划用同一份参数, 提成一个供给器给两处共用.
        // 覆盖层在首次规划时还是空的, 那时读到的就是 Inventory 自己的规划基准.
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
            // 空操作和被放入规则拒绝的点击同样是一次真实交互, 监听器看得到, 它们的写入也照样能落地.
            executor.executeUnplanned(clickType, hotbarButton, windowSlot, prepared.action());
        }
        if (prepared.handled() && windowSlot != InventoryView.OUTSIDE) {
            context.markDirty(windowSlot);
        }
        return prepared.handled();
    }

    // 拖拽同样先形成实际分配候选, Bukkit 事件看到的 newItems 与随后提交的候选完全一致.
    static void handleDrag(
            @NotNull ClickSemantics.Context context,
            @NotNull ClickType clickType,
            @NotNull List<Integer> windowSlots,
            @NotNull ClickSemantics.InteractionGate gate
    ) {
        // 拖拽的分配在派发之前就算好, 事件写的光标是最终值; 只有槽位写入才是需要重新解释的现场.
        InteractionOverlay overlay = InteractionOverlay.forDrag();
        DragPlanner.PreparedDrag prepared = DragPlanner.prepare(context, clickType, windowSlots, overlay);
        if (prepared != null) {
            ClickExecutor executor = new ClickExecutor(
                    context,
                    gate,
                    overlay,
                    () -> {
                        // 重规划后的分配结果可能与 Bukkit 事件看到的 newItems 不同, 事件按一次派发计.
                        DragPlanner.PreparedDrag replanned = DragPlanner.prepare(context, clickType, windowSlots, overlay);
                        return replanned == null ? null : replanned.candidate();
                    },
                    () -> "拖拽 " + clickType + " @ windowSlots " + windowSlots
            );
            executor.executeCandidate(prepared.candidate(), edits -> gate.allowDrag(prepared.newCursor().clone(), prepared.newItems(), edits));
        }
        markAllDirty(context, windowSlots);
    }

    // 处理点到窗口外的点击.
    static void handleOutsideClick(
            @NotNull ClickSemantics.Context context,
            @NotNull ClickType clickType
    ) {
        ItemStack cursor = context.cursor();
        if (cursor.isEmpty()) {
            return;
        }
        if (clickType == ClickType.WINDOW_BORDER_LEFT) {
            context.cursor(ItemUtils.EMPTY);
            context.drop(cursor.clone());
        } else {
            int left = cursor.getAmount() - 1;
            context.cursor(left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemUtils.EMPTY);
            context.drop(ItemUtils.copyWithAmount(cursor, 1));
        }
    }

    // 候选先经过 Bukkit 事件, 再经过 Sparrow 事件, 最后提交; 每道闸门前后都重新校验 Window 状态与候选基准.
    // 只有在闸门跑过用户代码之后才需要重新同步外部容器, 规划刚结束时用 staleReason 做纯身份校验就够了.
    // 两份草稿在第一道闸门之前就建好, 让途中的每个监听器都写进同一份结果.
    private void executeCandidate(ClickCandidate candidate, Predicate<InteractionEdits> bukkitStage) {
        if (candidate.staleReason(this.context) != null) {
            return;
        }
        boolean fireBukkitInventoryEvent = requestsBukkitInventoryEvent(candidate.eventTarget(), candidate.scopes());
        InteractionEdits edits = this.editsFor(candidate, this.overlay);
        if (!this.passGate(() -> !fireBukkitInventoryEvent || bukkitStage.test(edits))) {
            return;
        }
        // Bukkit 闸门是覆盖层的唯一写入者, 之后每一道闸门写进来的都是提交后的最终值.
        edits.closeOverlay();
        @Nullable ClickCandidate.StaleReason stale = this.recheck(candidate, fireBukkitInventoryEvent && this.gate.firesBukkitEvents(), edits);
        if (stale == null && this.overlay.isEmpty()) {
            this.finishCandidate(candidate, edits);
            return;
        }
        // 监听器往覆盖层里写了内容, 或者直接换掉了光标和容器, 本次结论所依据的现场已经变了.
        // 按闸门跑完之后的现场重算一次, 覆盖层里的内容是新结论的规划输入. 新结论没碰过的格子在结算时
        // 追加成最终值, 与结论进同一笔事务.
        @Nullable ClickCandidate replanned = this.replan.get();
        if (replanned == null) {
            // 新现场算不出候选. 监听器的写入与候选无关, 让它自成一笔事务.
            // 结论没了但交互还在, 与 executeUnplanned 一样, 被点的那一格照样收到一次点击事件.
            // action 跟着最终结论走, 这里已经没有结论, 所以报 NOTHING.
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

    // Sparrow 点击事件与提交. 重规划之后从这里继续, 因此这一段不含任何 Bukkit 事件.
    private void finishCandidate(ClickCandidate candidate, InteractionEdits edits) {
        ClickSemantics.LinkedSlot eventTarget = candidate.eventTarget();
        if (eventTarget != null) {
            if (!this.gate.stillValid()) {
                return;
            }
            // 有订阅者这道闸门才真的跑用户代码. 读在派发前一刻, 订阅者在事件里退订也不影响这次的答案.
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

    // 算不出候选的点击没有规划基准, 也就没有候选可以作废. 两份草稿都等到第一次写入才建, 没人写就什么都不提交.
    // 这一格背后有 Inventory 时, 它照样参与了这次交互, 因此 Sparrow 点击事件与 Bukkit 点击事件一起派发.
    private void executeUnplanned(ClickType clickType, int hotbarButton, int windowSlot, InventoryAction action) {
        InteractionEdits edits = new InteractionEdits(this.context, null, null, this.overlay);
        ItemStack plannedCursor = this.context.cursor();
        boolean fireBukkitInventoryEvent = requestsBukkitInventoryEvent(this.context.linkAt(windowSlot), List.of());
        if (!this.passGate(() -> !fireBukkitInventoryEvent || this.gate.allowClick(action, edits))) {
            return;
        }
        edits.closeOverlay();
        // 规划期算不出候选往往只是因为当时光标为空或者装不下. 闸门改掉了现场就按新现场重算一次,
        // 这次可能真的有事可做. 这条路径没有候选也就没有读集, 容器自己被换掉检测不到.
        if (!this.overlay.isEmpty() || !ItemUtils.isContentEqual(plannedCursor, this.context.cursor())) {
            @Nullable ClickCandidate replanned = this.replan.get();
            if (replanned != null && replanned.staleReason(this.context) == null) {
                // 重算出的候选自带事件目标, Sparrow 点击事件跟着它派发.
                this.finishCandidate(replanned, this.settled(replanned));
                return;
            }
        }
        // 结算要赶在 Sparrow 事件之前, 事件读到的是 Bukkit 监听器留下的结果, 写下的又交给随后的提交.
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

    // 提交一笔只有监听器写入的交互. 没有候选就没有规划基准可以复核, 唯一要确认的是监听器写完之后
    // 菜单实际光标还是那一份. 草稿写的是最终值, 光标被换过就会被它盖掉.
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

    // 闸门之后的复核. 闸门真的跑过用户代码才把外部容器重新同步进来, 没跑过时规划基准还是刚读的那份, 纯身份比对就够了.
    @Nullable
    private ClickCandidate.StaleReason recheck(ClickCandidate candidate, boolean userCodeRan, InteractionEdits edits) {
        return stale(userCodeRan ? candidate.revalidate(this.context) : candidate.staleReason(this.context), edits);
    }

    // 候选自身的前提之外再兜一层. 候选不复核光标(shift, 数字键, 换副手)时, 监听器写进草稿的光标同样是
    // 相对某一份现场算出的最终值, 光标被换过它就不能合并.
    @Nullable
    private static ClickCandidate.StaleReason stale(@Nullable ClickCandidate.StaleReason reason, InteractionEdits edits) {
        return reason != null ? reason : edits.staleCursor();
    }

    // 为候选建好两份草稿的写入句柄. 写集非空的候选在闸门之前就建好草稿, 形状非法的事务在任何监听器
    // 动手之前就被拒. 写集为空的候选(创造模式复制等)本身不进事务, 闸门写入仍可能给它懒建出一份.
    // 挂上覆盖层的句柄用于 Bukkit 闸门, 那一段的写入先攒进现场, 结算之后的句柄一律不挂.
    @NotNull
    private InteractionEdits editsFor(ClickCandidate candidate, @Nullable InteractionOverlay overlay) {
        @Nullable TransactionDraft planned = candidate.scopes().isEmpty()
                ? null
                : new TransactionDraft(candidate.scopes());
        return new InteractionEdits(this.context, planned, candidate.draft(), overlay);
    }

    // 把闸门留下的现场覆盖结算进按新现场重建的句柄. 新现场算不出候选时另起一份空句柄, 旧句柄上挂着
    // 作废候选自己的副作用草稿, 沿用它会把那份规划期光标一起提交掉.
    @NotNull
    private InteractionEdits settled(@Nullable ClickCandidate replanned) {
        InteractionEdits target = replanned == null
                ? new InteractionEdits(this.context, null, null, null)
                : this.editsFor(replanned, null);
        target.settle(this.overlay, replanned);
        return target;
    }

    // 事件派发前后各复核一次 Window 状态, 处理器自己可能关掉或重开 Window.
    private boolean passGate(BooleanSupplier stage) {
        return this.gate.stillValid() && stage.getAsBoolean() && this.gate.stillValid();
    }

    // 根据本次参与交互的 Inventory 集合, 判断是否应在交互时触发 Bukkit 的相关事件
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

    // 把复核结果转成布尔, 顺便在候选被静默丢弃时给插件作者留一条线索.
    // 三处复核只要有一处失败就直接返回, 所以一次交互至多报出一条.
    private boolean survived(@Nullable ClickCandidate.StaleReason reason) {
        if (reason == null) {
            return true;
        }
        // 基准状态变了是正常并发(另一笔事务提交, 或者刷新引用根拉进了外部变更), 报了只是噪音.
        // 光标对不上走到这里, 说明有人在最后一道闸门之后才直接换掉菜单实际光标. 重规划已经过去,
        // 本次结论算的仍是换掉之前那份光标, 提交它会盖掉那次改动, 只能整体放弃, 值得说一声.
        if (reason == ClickCandidate.StaleReason.CURSOR) {
            SparrowUI.getInstance().warn(this.describe.get() + " 被丢弃: 提交之前直接改掉了菜单实际光标"
                    + "(如 HumanEntity#setItemOnCursor), 本次结论依据的是改动之前那份光标, 提交它会盖掉这次改动."
                    + " 请改用交互写入句柄的 cursor(...).");
        }
        return false;
    }

    // 玩家侧只读的 Inventory 与冻结槽同待遇, 点它展示槽连空操作事件都不派发, 只纠正客户端预测.
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
