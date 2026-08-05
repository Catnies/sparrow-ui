package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
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

/**
 * 点击语义的执行端: 拿 {@link ClickPlanner} 算好的候选, 依次过闸门, 最后提交事务.
 * <p>候选在每道闸门前后重新校验. Bukkit 闸门之后是唯一的重规划点: 监听器在那里写下的槽位与光标先攒进
 * {@link InteractionOverlay}, 表达的是"这一格现在就是这个值"; 只要有覆盖或候选前提变了, 就按闸门跑完
 * 之后的现场重算一次候选再继续, 与原版"事件之后的现场说了算"一致. 覆盖里没有被新结论消费掉的部分在
 * 结算时追加成最终值, 与结论进同一笔事务. 重规划至多一次, 也不会重新派发 Bukkit 事件.
 * 其余位置的校验失败一律整体放弃.
 */
final class ClickExecutor {

    private ClickExecutor() {
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
        // 首次规划与闸门之后的重规划用同一份参数, 提成一个供给器免得把这串实参抄两遍.
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
        Supplier<ClickCandidate> replan = () -> plan.get().candidate();
        ClickPlanner.PreparedClick prepared = plan.get();
        ClickCandidate candidate = prepared.candidate();
        Supplier<String> describe = () -> "点击 " + clickType + " @ windowSlot " + windowSlot;
        if (candidate != null) {
            executeCandidate(context, candidate, gate, edits -> gate.allowClick(candidate.action(), edits), replan, describe, overlay);
        } else if (prepared.handled() && !context.frozenAt(windowSlot)) {
            // 空操作和被放入规则拒绝的点击同样是一次真实交互: 监听器看得到, 它们的写入也照样能落地.
            executeUnplanned(context, clickType, hotbarButton, prepared.action(), gate, replan, describe, overlay);
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
        ClickPlanner.PreparedDrag prepared = ClickPlanner.prepareDrag(context, clickType, windowSlots, overlay);
        if (prepared != null) {
            executeCandidate(
                    context,
                    prepared.candidate(),
                    gate,
                    edits -> gate.allowDrag(prepared.newCursor().clone(), prepared.newItems(), edits),
                    () -> {
                        // 重规划后的分配结果可能与 Bukkit 事件看到的 newItems 不同; 事件只派发一次, 不再重发.
                        ClickPlanner.PreparedDrag replanned = ClickPlanner.prepareDrag(context, clickType, windowSlots, overlay);
                        return replanned == null ? null : replanned.candidate();
                    },
                    () -> "拖拽 " + clickType + " @ windowSlots " + windowSlots,
                    overlay
            );
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
            context.cursor(ItemStack.empty());
            context.drop(cursor.clone());
        } else {
            int left = cursor.getAmount() - 1;
            context.cursor(left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemStack.empty());
            context.drop(ItemUtils.copyWithAmount(cursor, 1));
        }
    }

    // 候选先经过 Bukkit 事件, 再经过 Sparrow 事件, 最后提交; 每道闸门前后都重新校验 Window 状态与候选基准.
    // 只有在闸门跑过用户代码之后才需要重新同步外部容器, 规划刚结束时用 staleReason 做纯身份校验就够了.
    // 两份草稿在第一道闸门之前就建好, 让途中的每个监听器都写进同一份结果.
    private static void executeCandidate(
            ClickSemantics.Context context,
            ClickCandidate candidate,
            ClickSemantics.InteractionGate gate,
            Predicate<InteractionEdits> bukkitStage,
            Supplier<@Nullable ClickCandidate> replan,
            Supplier<String> describe,
            InteractionOverlay overlay
    ) {
        if (candidate.staleReason(context) != null) {
            return;
        }
        InteractionEdits edits = editsFor(context, candidate, describe, overlay);
        if (!passGate(gate, () -> bukkitStage.test(edits))) {
            return;
        }
        // Bukkit 闸门是覆盖层的唯一写入者, 之后每一道闸门写进来的都是提交后的最终值.
        edits.closeOverlay();
        @Nullable ClickCandidate.StaleReason stale = stale(candidate.revalidate(context), edits);
        if (stale == null && overlay.isEmpty()) {
            finishCandidate(context, candidate, edits, gate, describe);
            return;
        }
        // 监听器改掉了本次结论所依据的现场 —— 或是往覆盖层里写了内容, 或是直接换掉了光标和容器 ——
        // 按闸门跑完之后的现场重算一次. 覆盖层里的内容是新结论的规划输入; 新结论没碰过的格子在结算时
        // 追加成最终值, 与结论进同一笔事务.
        @Nullable ClickCandidate replanned = replan.get();
        if (replanned == null) {
            // 新现场算不出候选. 监听器的写入与候选无关, 不该跟着候选一起丢掉, 让它自成一笔事务.
            commitEdits(context, candidate.reason(), settled(context, null, overlay, describe), gate, describe);
            return;
        }
        if (replanned.staleReason(context) != null) {
            return;
        }
        finishCandidate(context, replanned, settled(context, replanned, overlay, describe), gate, describe);
    }

    // Sparrow 点击事件与提交. 重规划之后从这里继续, 因此这一段不含任何 Bukkit 事件.
    private static void finishCandidate(
            ClickSemantics.Context context,
            ClickCandidate candidate,
            InteractionEdits edits,
            ClickSemantics.InteractionGate gate,
            Supplier<String> describe
    ) {
        ClickSemantics.LinkedSlot eventTarget = candidate.eventTarget();
        if (eventTarget != null
                && (!passGate(gate, () -> gate.allowInventoryClick(eventTarget, candidate.action(), edits))
                || !survived(stale(candidate.revalidate(context), edits), describe))) {
            return;
        }
        @Nullable TransactionDraft draft = edits.transaction();
        if (draft == null) {
            if (survived(stale(candidate.staleReason(context), edits), describe) && gate.stillValid()) {
                candidate.draft().seal();
                candidate.applyAfterCommit(context);
            }
            return;
        }
        InventoryTransactions.commit(
                candidate.reason(),
                draft,
                candidate.draft(),
                false,
                () -> candidate.applyAfterCommit(context),
                candidate.plannedRoots(),
                () -> survived(stale(candidate.staleReason(context), edits), describe) && gate.stillValid()
        );
    }

    // 候选自身的前提之外再兜一层: 候选不复核光标(shift, 数字键, 换副手)时, 监听器写进草稿的光标同样是
    // 相对某一份现场算出的最终值, 被人直接换掉照样不能合并.
    @Nullable
    private static ClickCandidate.StaleReason stale(@Nullable ClickCandidate.StaleReason reason, InteractionEdits edits) {
        return reason != null ? reason : edits.staleCursor();
    }

    // 为候选建好两份草稿的写入句柄. 写集非空的候选必须在闸门之前就建好草稿: 形状非法的事务要赶在任何监听器
    // 写入之前被拒绝. 写集为空的候选(创造模式复制等)本身不进事务, 但闸门写入仍可能给它懒建出一份.
    // 挂上覆盖层的句柄用于 Bukkit 闸门, 那一段的写入先攒进现场; 结算之后的句柄一律不挂.
    @NotNull
    private static InteractionEdits editsFor(
            ClickSemantics.Context context,
            ClickCandidate candidate,
            Supplier<String> describe,
            @Nullable InteractionOverlay overlay
    ) {
        @Nullable TransactionDraft planned = candidate.scopes().isEmpty()
                ? null
                : new TransactionDraft(candidate.scopes());
        return new InteractionEdits(context, planned, candidate.draft(), describe, overlay);
    }

    // 把闸门留下的现场覆盖结算进按新现场重建的句柄. 新现场算不出候选时不能沿用旧句柄: 那里挂着作废候选
    // 自己的副作用草稿, 会把它规划期算的光标一起提交掉.
    @NotNull
    private static InteractionEdits settled(
            ClickSemantics.Context context,
            @Nullable ClickCandidate replanned,
            InteractionOverlay overlay,
            Supplier<String> describe
    ) {
        InteractionEdits target = replanned == null
                ? new InteractionEdits(context, null, null, describe, null)
                : editsFor(context, replanned, describe, null);
        target.settle(overlay, replanned);
        return target;
    }

    // 算不出候选的点击没有规划基准, 也就没有候选可以作废. 两份草稿都等到第一次写入才建, 没人写就什么都不提交.
    private static void executeUnplanned(
            ClickSemantics.Context context,
            ClickType clickType,
            int hotbarButton,
            InventoryAction action,
            ClickSemantics.InteractionGate gate,
            Supplier<@Nullable ClickCandidate> replan,
            Supplier<String> describe,
            InteractionOverlay overlay
    ) {
        InteractionEdits edits = new InteractionEdits(context, null, null, describe, overlay);
        ItemStack plannedCursor = context.cursor();
        if (!passGate(gate, () -> gate.allowClick(action, edits))) {
            return;
        }
        edits.closeOverlay();
        // 规划期算不出候选往往只是因为当时光标为空或者装不下. 闸门改掉了现场就按新现场重算一次:
        // 这次可能真的有事可做. 容器自己被换掉在这条路径上检测不到 —— 没有候选就没有读集当基准.
        if (!overlay.isEmpty() || !plannedCursor.equals(context.cursor())) {
            @Nullable ClickCandidate replanned = replan.get();
            if (replanned != null && replanned.staleReason(context) == null) {
                finishCandidate(context, replanned, settled(context, replanned, overlay, describe), gate, describe);
                return;
            }
        }
        commitEdits(
                context,
                new PlayerUpdateReason.Click(context.viewer(), clickType, clickType == ClickType.NUMBER_KEY ? hotbarButton : -1),
                settled(context, null, overlay, describe),
                gate,
                describe
        );
    }

    // 提交一笔只有监听器写入的交互. 没有候选就没有规划基准可以复核, 唯一要确认的是没有人在监听器写完之后
    // 又直接换掉菜单实际光标 —— 草稿写的是最终值, 会悄悄盖掉那次改动.
    private static void commitEdits(
            ClickSemantics.Context context,
            UpdateReason reason,
            InteractionEdits edits,
            ClickSemantics.InteractionGate gate,
            Supplier<String> describe
    ) {
        @Nullable InteractionDraft interaction = edits.interaction();
        @Nullable TransactionDraft draft = edits.transaction();
        if (draft == null) {
            if (interaction != null && survived(edits.staleCursor(), describe) && gate.stillValid()) {
                interaction.seal();
                interaction.apply(context);
            }
            return;
        }
        InventoryTransactions.commit(
                reason,
                draft,
                interaction,
                false,
                interaction == null ? null : () -> interaction.apply(context),
                List.of(),
                () -> survived(edits.staleCursor(), describe) && gate.stillValid()
        );
    }

    // 事件派发前后各复核一次 Window 状态: 处理器自己可能关掉或重开 Window.
    private static boolean passGate(ClickSemantics.InteractionGate gate, BooleanSupplier stage) {
        return gate.stillValid() && stage.getAsBoolean() && gate.stillValid();
    }

    // 把复核结果转成布尔, 顺便在候选被静默丢弃时给插件作者留一条线索.
    // 三处复核只要有一处失败就直接返回, 所以一次交互至多报出一条.
    private static boolean survived(@Nullable ClickCandidate.StaleReason reason, Supplier<String> describe) {
        if (reason == null) {
            return true;
        }
        // 基准状态变了是正常并发(另一笔事务提交, 或者刷新引用根拉进了外部变更), 报了只是噪音.
        // 光标对不上走到这里, 说明有人在最后一道闸门之后才直接换掉菜单实际光标 —— 重规划已经过去了,
        // 本次结论算的仍是换掉之前那份光标, 提交它会悄悄盖掉那次改动, 只能整体放弃, 值得说一声. // todo 改英文
        if (reason == ClickCandidate.StaleReason.CURSOR) {
            SparrowUI.getInstance().warn(describe.get() + " 被丢弃: 提交之前直接改掉了菜单实际光标"
                    + "(如 HumanEntity#setItemOnCursor), 本次结论依据的是改动之前那份光标, 提交它会盖掉这次改动."
                    + " 请改用交互写入句柄的 cursor(...).");
        }
        return false;
    }

    private static void markAllDirty(ClickSemantics.Context context, List<Integer> windowSlots) {
        for (int windowIndex = 0; windowIndex < windowSlots.size(); windowIndex++) {
            context.markDirty(windowSlots.get(windowIndex));
        }
    }
}
