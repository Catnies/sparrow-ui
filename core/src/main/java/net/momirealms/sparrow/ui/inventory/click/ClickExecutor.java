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

// 候选依次经过 Bukkit 与 Sparrow 闸门, 用户代码运行后重新校验, 必要时至多重规划一次.
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
            // 没有候选的真实交互仍允许监听器写入自己的结果.
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
        // 拖拽的分配在派发之前就算好, 事件写的光标是最终值.
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

    // Bukkit 闸门结束后校验候选, 现场变化时重规划一次再进入 Sparrow 闸门.
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
        @Nullable ClickCandidate replanned = this.replan.get();
        if (replanned == null) {
            // 新现场没有候选, 监听器写入作为独立事务继续处理.
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

    // 没有候选时按需创建草稿, 只提交监听器实际写入的内容.
    private void executeUnplanned(ClickType clickType, int hotbarButton, int windowSlot, InventoryAction action) {
        InteractionEdits edits = new InteractionEdits(this.context, null, null, this.overlay);
        ItemStack plannedCursor = this.context.cursor();
        boolean fireBukkitInventoryEvent = requestsBukkitInventoryEvent(this.context.linkAt(windowSlot), List.of());
        if (!this.passGate(() -> !fireBukkitInventoryEvent || this.gate.allowClick(action, edits))) {
            return;
        }
        edits.closeOverlay();
        // Bukkit 闸门改变现场后仍有一次形成候选的机会.
        if (!this.overlay.isEmpty() || !ItemUtils.isHandleContentEqual(this.context.unsafeCursor(), plannedCursor)) {
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

    // 无候选写入只复核监听器写入时看到的光标和当前 Window 状态.
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

    // 用户代码运行后同步外部存储, 纯放行路径只比较已有基准.
    @Nullable
    private ClickCandidate.StaleReason recheck(ClickCandidate candidate, boolean userCodeRan, InteractionEdits edits) {
        return stale(userCodeRan ? candidate.revalidate(this.context) : candidate.staleReason(this.context), edits);
    }

    @Nullable
    private static ClickCandidate.StaleReason stale(@Nullable ClickCandidate.StaleReason reason, InteractionEdits edits) {
        return reason != null ? reason : edits.staleCursor();
    }

    // 非空写集在用户代码运行前完成形状校验.
    @NotNull
    private InteractionEdits editsFor(ClickCandidate candidate, @Nullable InteractionOverlay overlay) {
        @Nullable TransactionDraft planned = candidate.scopes().isEmpty()
                ? null
                : new TransactionDraft(candidate.scopes());
        return new InteractionEdits(this.context, planned, candidate.draft(), overlay);
    }

    // 作废候选的副作用草稿不可带入重规划结果.
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

    // 光标被直接改写会使候选覆盖较新的值, 因此丢弃并给出可操作的告警.
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
