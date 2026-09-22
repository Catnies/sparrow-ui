package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.InventoryPlanner;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.click.rules.ClickActions;
import net.momirealms.sparrow.ui.inventory.click.rules.ClickOutcome;
import net.momirealms.sparrow.ui.inventory.click.rules.ClickSlotRules;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.storage.SlotKey;
import net.momirealms.sparrow.ui.inventory.transaction.InteractionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

// 这里只算不写, 一格都不会落地. 读的是当前 Window 状态叠上覆盖层里监听器的那些改动.
final class ClickPlanner {

    // 算一次单击. handled 说的是这一格归不归点击语义管, 跟算不算得出候选是两件事.
    @NotNull
    static PreparedClick prepareClick(
            ClickSemantics.Context context,
            ClickType clickType,
            int hotbarButton,
            int windowSlot,
            @Nullable ItemStack observedBundle,
            int selectedIndex,
            Runnable afterCommit,
            boolean write,
            InteractionOverlay overlay
    ) {
        if (windowSlot == InventoryView.OUTSIDE) {
            return new PreparedClick(false, ClickActions.outsideAction(overlay.cursorOr(context.cursor()), clickType), null);
        }
        // 认不出来的点击类型原样报 UNKNOWN, 交给上层决定. 冻结与否只影响后面要不要发事件, 不改这个结论.
        if (clickType == ClickType.UNKNOWN || clickType == ClickType.CREATIVE) {
            return new PreparedClick(context.frozenAt(windowSlot) || context.linkAt(windowSlot) != null, InventoryAction.UNKNOWN, null);
        }
        // 冻结槽归引擎管, 但什么都不改, 只把客户端猜出来的画面纠回去.
        if (context.frozenAt(windowSlot)) {
            return new PreparedClick(true, InventoryAction.NOTHING, null);
        }

        ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
        if (link == null) {
            return new PreparedClick(false, InventoryAction.NOTHING, null);
        }
        if (link.inventory().frozen()) {
            return new PreparedClick(true, InventoryAction.NOTHING, null);
        }

        ClickCandidate candidate = switch (clickType) {
            case LEFT, RIGHT -> preparePickupOrPlace(
                    context,
                    link,
                    clickType,
                    observedBundle,
                    selectedIndex,
                    afterCommit,
                    write,
                    overlay
            );
            case SHIFT_LEFT, SHIFT_RIGHT -> prepareShift(context, link, clickType, write, overlay);
            case NUMBER_KEY -> prepareHotbarSwap(context, link, hotbarButton, write, overlay);
            case SWAP_OFFHAND -> prepareOffhandSwap(context, link, write, overlay);
            case DROP, CONTROL_DROP -> prepareDrop(context, link, clickType == ClickType.CONTROL_DROP, write, overlay);
            case DOUBLE_CLICK -> prepareLinkedCollect(context, link, windowSlot, write, overlay);
            case MIDDLE -> prepareCreativeClone(context, link, write, overlay);
            default -> null;
        };
        InventoryAction action = clickType == ClickType.NUMBER_KEY && (hotbarButton < 0 || hotbarButton > 8)
                ? InventoryAction.UNKNOWN
                : actionOf(candidate);
        return new PreparedClick(true, action, candidate == null ? null : candidate.withRealBefore(overlay));
    }

    @Nullable
    private static ClickCandidate preparePickupOrPlace(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot link,
            ClickType clickType,
            @Nullable ItemStack observedBundle,
            int selectedIndex,
            Runnable afterCommit,
            boolean write,
            InteractionOverlay overlay
    ) {
        ItemStack actualCursor = context.cursor();
        ItemStack cursor = overlay.cursorOr(actualCursor);
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), clickType, -1);
        SparrowInventory inventory = link.inventory();
        PlannedRoot plan = openPlan(inventory, write);
        @Nullable ItemStack current = overlay.viewOf(plan)[link.slot()];
        ClickOutcome outcome = clickType == ClickType.LEFT
                ? ClickSlotRules.computeLeftClick(current, cursor, inventory.slotMaxStackSize(link.slot()))
                : ClickSlotRules.computeRightClick(
                        current,
                        cursor,
                        inventory.slotMaxStackSize(link.slot()),
                        observedBundle,
                        selectedIndex
                );
        if (outcome == null) {
            return null;
        }

        SlotChange delta = new SlotChange(link.slot(), current, outcome.slotAfter());
        boolean allowed = outcome.addedItem() != null || outcome.removedItem() != null
                ? inventory.allowsAccess(reason, context.window(), delta, outcome.addedItem(), outcome.removedItem())
                : inventory.allowsAccess(reason, context.window(), delta);
        if (!allowed) {
            return null;
        }

        InventoryAction action = clickType == ClickType.LEFT
                ? ClickActions.leftAction(current, cursor, outcome)
                : ClickActions.rightAction(current, cursor);
        List<TransactionScope> scopes = List.of(new TransactionScope(plan, List.of(delta)));
        return ClickCandidate.plan(action, reason)
                .eventTarget(link)
                .scopes(scopes)
                .reads(List.of(plan))
                .checkCursor(actualCursor)
                .draft(InteractionDraft.cursorAfter(outcome.cursorAfter()))
                .afterCommit(afterCommit)
                .build();
    }

    @Nullable
    private static ClickCandidate prepareHotbarSwap(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot source,
            int hotbarButton,
            boolean write,
            InteractionOverlay overlay
    ) {
        if (hotbarButton < 0 || hotbarButton > 8) {
            return null;
        }
        ClickSemantics.LinkedSlot target = context.hotbarLink(hotbarButton);
        if (target == null || source.physicalKey().equals(target.physicalKey())) {
            return null;
        }
        if (target.inventory().frozen()) {
            return null;
        }

        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), ClickType.NUMBER_KEY, hotbarButton);
        PlannedRoot sourcePlan = openPlan(source.inventory(), write);
        PlannedRoot targetPlan = source.inventory() == target.inventory()
                ? sourcePlan
                : openPlan(target.inventory(), write);
        @Nullable ItemStack sourceItem = overlay.viewOf(sourcePlan)[source.slot()];
        @Nullable ItemStack targetItem = overlay.viewOf(targetPlan)[target.slot()];
        if (Objects.equals(sourceItem, targetItem)) {
            return null;
        }
        if (!fitsReceiving(source, targetItem) || !fitsReceiving(target, sourceItem)) {
            return null;
        }
        if (!source.inventory().allowsAccess(reason, context.window(), new SlotChange(source.slot(), sourceItem, targetItem))
                || !target.inventory().allowsAccess(reason, context.window(), new SlotChange(target.slot(), targetItem, sourceItem))) {
            return null;
        }

        List<TransactionScope> scopes;
        if (source.inventory() == target.inventory()) {
            scopes = List.of(new TransactionScope(sourcePlan, List.of(
                    new SlotChange(source.slot(), sourceItem, targetItem),
                    new SlotChange(target.slot(), targetItem, sourceItem)
            )));
        } else {
            scopes = List.of(
                    new TransactionScope(sourcePlan, List.of(new SlotChange(source.slot(), sourceItem, targetItem))),
                    new TransactionScope(targetPlan, List.of(new SlotChange(target.slot(), targetItem, sourceItem)))
            );
        }
        return ClickCandidate.plan(InventoryAction.HOTBAR_SWAP, reason)
                .eventTarget(source)
                .scopes(scopes)
                .reads(sourcePlan == targetPlan ? List.of(sourcePlan) : List.of(sourcePlan, targetPlan))
                .build();
    }

    @Nullable
    private static ClickCandidate prepareOffhandSwap(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot source,
            boolean write,
            InteractionOverlay overlay
    ) {
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), ClickType.SWAP_OFFHAND, -1);
        PlannedRoot plan = openPlan(source.inventory(), write);
        @Nullable ItemStack current = overlay.viewOf(plan)[source.slot()];
        @Nullable ItemStack offhand = ItemUtils.nullIfEmpty(context.offhand());
        if (Objects.equals(current, offhand)) {
            return null;
        }
        if (!fitsReceiving(source, offhand) || !source.inventory().allowsAccess(reason, context.window(), new SlotChange(source.slot(), current, offhand))) {
            return null;
        }

        List<TransactionScope> scopes = List.of(new TransactionScope(plan, List.of(
                new SlotChange(source.slot(), current, offhand)
        )));
        return ClickCandidate.plan(InventoryAction.HOTBAR_SWAP, reason)
                .eventTarget(source)
                .scopes(scopes)
                .reads(List.of(plan))
                .checkOffhand(offhand)
                .draft(InteractionDraft.offhandAfter(ItemUtils.copyOrNull(current)))
                .build();
    }

    // 交换是整堆换整堆, 不拆分. 所以接收方那一格装不下整堆的时候, 这次交换就不成立.
    private static boolean fitsReceiving(
            ClickSemantics.LinkedSlot target,
            @Nullable ItemStack incoming
    ) {
        if (incoming == null) {
            return true;
        }
        int limit = Math.min(target.inventory().slotMaxStackSize(target.slot()), incoming.getMaxStackSize());
        return incoming.getAmount() <= limit;
    }

    @Nullable
    private static ClickCandidate prepareDrop(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot link,
            boolean fullStack,
            boolean write,
            InteractionOverlay overlay
    ) {
        ItemStack actualCursor = context.cursor();
        if (!ItemUtils.isEmpty(overlay.cursorOr(actualCursor))) {
            return null;
        }
        ClickType clickType = fullStack ? ClickType.CONTROL_DROP : ClickType.DROP;
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), clickType, -1);
        PlannedRoot plan = openPlan(link.inventory(), write);
        @Nullable ItemStack current = overlay.viewOf(plan)[link.slot()];
        if (current == null) {
            return null;
        }
        int take = fullStack ? current.getAmount() : 1;
        int left = current.getAmount() - take;
        SlotChange delta = new SlotChange(link.slot(), current, left > 0 ? ItemUtils.copyWithAmount(current, left) : null);
        if (!link.inventory().allowsAccess(reason, context.window(), delta)) {
            return null;
        }
        List<TransactionScope> scopes = List.of(new TransactionScope(plan, List.of(delta)));
        return ClickCandidate.plan(fullStack ? InventoryAction.DROP_ALL_SLOT : InventoryAction.DROP_ONE_SLOT, reason)
                .eventTarget(link)
                .scopes(scopes)
                .reads(List.of(plan))
                .checkCursor(actualCursor)
                .draft(InteractionDraft.dropped(ItemUtils.copyWithAmount(current, take)))
                .build();
    }

    @Nullable
    private static ClickCandidate prepareCreativeClone(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot link,
            boolean write,
            InteractionOverlay overlay
    ) {
        ItemStack actualCursor = context.cursor();
        if (context.viewer().getGameMode() != GameMode.CREATIVE || !ItemUtils.isEmpty(overlay.cursorOr(actualCursor))) {
            return null;
        }
        PlannedRoot plan = openPlan(link.inventory(), write);
        @Nullable ItemStack current = overlay.viewOf(plan)[link.slot()];
        if (current == null) {
            return null;
        }
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), ClickType.MIDDLE, -1);
        if (!link.inventory().allowsAccess(reason, context.window(), new SlotChange(link.slot(), current, current))) {
            return null;
        }
        return ClickCandidate.plan(InventoryAction.CLONE_STACK, reason)
                .eventTarget(link)
                .reads(List.of(plan))
                .checkCursor(actualCursor)
                .requireCreative(true)
                .draft(InteractionDraft.cursorAfter(ItemUtils.copyWithAmount(current, current.getMaxStackSize())))
                .build();
    }

    // 双击收集. 客户端双击会连着发两个包, 第二个包落在被点的那一格上;
    // 只有那一格看起来是空的、真实内容也确实是空的, 才把它当收集处理, 否则就是普通的第二次点击.
    @Nullable
    private static ClickCandidate prepareLinkedCollect(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot clicked,
            int windowSlot,
            boolean write,
            InteractionOverlay overlay
    ) {
        if (ItemUtils.isEmpty(overlay.cursorOr(context.cursor())) || !context.displayedEmptyAt(windowSlot)) {
            return null;
        }
        IdentityHashMap<SparrowInventory, PlannedRoot> plans = new IdentityHashMap<>();
        PlannedRoot clickedPlan = openPlan(clicked.inventory(), write);
        plans.put(clicked.inventory(), clickedPlan);
        if (overlay.viewOf(clickedPlan)[clicked.slot()] != null) {
            return null;
        }
        return prepareCollect(context, clicked, write, plans, overlay);
    }

    @Nullable
    private static ClickCandidate prepareCollect(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot clicked,
            boolean write,
            IdentityHashMap<SparrowInventory, PlannedRoot> plans,
            InteractionOverlay overlay
    ) {
        ItemStack actualCursor = context.cursor();
        ItemStack cursor = overlay.cursorOr(actualCursor);
        int space = cursor.getMaxStackSize() - cursor.getAmount();
        if (space <= 0) {
            return null;
        }
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), ClickType.DOUBLE_CLICK, -1);
        int collected = 0;
        HashSet<SlotKey> coveredSlots = new HashSet<>();
        List<TransactionScope> scopes = new ArrayList<>();

        List<ClickSemantics.LinkedInventory> domain = new ArrayList<>(context.linkedInventories());
        domain.sort((left, right) -> Integer.compare(
                right.inventory().operationPriority(OperationCategory.COLLECT),
                left.inventory().operationPriority(OperationCategory.COLLECT)
        ));
        for (int inventoryIndex = 0; inventoryIndex < domain.size() && collected < space; inventoryIndex++) {
            ClickSemantics.LinkedInventory linked = domain.get(inventoryIndex);
            SparrowInventory inventory = linked.inventory();
            if (inventory.frozen()) {
                continue;
            }
            PlannedRoot plan = plans.computeIfAbsent(inventory, key -> openPlan(key, write));
            InventoryPlanner.TakePlan takePlan = InventoryPlanner.planCollect(
                    overlay.viewOf(plan),
                    cursor,
                    space - collected,
                    inventory.iterationOrder(OperationCategory.COLLECT),
                    // 先看这一格 Pane 有没有展示出来, 再认领它的物理槽.
                    // 同一格真实位置可能被两个 Inventory 同时映射出来, 谁先认领算谁的, 免得同一格被收两遍.
                    delta -> linked.visible(delta.slot()) && coveredSlots.add(inventory.physicalKey(delta.slot())) && inventory.allowsAccess(reason, context.window(), delta),
                    inventory::slotMaxStackSize
            );
            if (!takePlan.deltas().isEmpty()) {
                scopes.add(new TransactionScope(plan, takePlan.deltas()));
            }
            collected += takePlan.taken();
        }
        if (collected <= 0) {
            return null;
        }

        return ClickCandidate.plan(InventoryAction.COLLECT_TO_CURSOR, reason)
                .eventTarget(clicked)
                .scopes(scopes)
                .reads(new ArrayList<>(plans.values()))
                .checkCursor(actualCursor)
                .draft(InteractionDraft.cursorAfter(ItemUtils.copyWithAmount(cursor, cursor.getAmount() + collected)))
                .build();
    }

    // Shift 快速转移. 依次试每个目标能接多少, 前面接走多少直接决定后面还剩多少,
    // 所以凡是被试过的目标都得进读集, <strong>哪怕它一个物品都没接</strong>, 否则它在提交前被人改了也发现不了.
    @Nullable
    private static ClickCandidate prepareShift(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot source,
            ClickType clickType,
            boolean write,
            InteractionOverlay overlay
    ) {
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), clickType, -1);
        PlannedRoot sourcePlan = openPlan(source.inventory(), write);
        @Nullable ItemStack current = overlay.viewOf(sourcePlan)[source.slot()];
        if (current == null) {
            return null;
        }

        HashSet<SlotKey> coveredSlots = new HashSet<>();
        coveredSlots.add(source.physicalKey());
        List<TransactionScope> targetScopes = new ArrayList<>();
        List<PlannedRoot> readPlans = new ArrayList<>();
        readPlans.add(sourcePlan);
        int remaining = current.getAmount();

        List<ClickSemantics.LinkedInventory> targets = addTargets(context, source.inventory());
        for (int targetIndex = 0; targetIndex < targets.size() && remaining > 0; targetIndex++) {
            ClickSemantics.LinkedInventory linked = targets.get(targetIndex);
            SparrowInventory target = linked.inventory();
            PlannedRoot targetPlan = openPlan(target, write);
            readPlans.add(targetPlan);
            InventoryPlanner.AddPlan addPlan = InventoryPlanner.planAdd(
                    overlay.viewOf(targetPlan),
                    ItemUtils.copyWithAmount(current, remaining),
                    target.iterationOrder(OperationCategory.ADD),
                    target::slotMaxStackSize,
                    // 同样是先看可见性再认领物理槽, 避免一格真实位置被两个 Inventory 各放一份.
                    delta -> linked.visible(delta.slot()) && coveredSlots.add(target.physicalKey(delta.slot())) && target.allowsAccess(reason, context.window(), delta)
            );
            if (!addPlan.deltas().isEmpty()) {
                targetScopes.add(new TransactionScope(targetPlan, addPlan.deltas()));
            }
            remaining = addPlan.remaining();
        }
        int moved = current.getAmount() - remaining;
        if (moved <= 0) {
            return null;
        }

        SlotChange sourceChange = new SlotChange(source.slot(), current, remaining > 0 ? ItemUtils.copyWithAmount(current, remaining) : null);
        if (!source.inventory().allowsAccess(reason, context.window(), sourceChange)) {
            return null;
        }
        List<TransactionScope> scopes = new ArrayList<>(targetScopes.size() + 1);
        scopes.add(new TransactionScope(sourcePlan, List.of(sourceChange)));
        scopes.addAll(targetScopes);
        return ClickCandidate.plan(InventoryAction.MOVE_TO_OTHER_INVENTORY, reason)
                .eventTarget(source)
                .scopes(scopes)
                .reads(readPlans)
                .build();
    }

    @NotNull
    private static List<ClickSemantics.LinkedInventory> addTargets(
            ClickSemantics.Context context,
            SparrowInventory source
    ) {
        List<ClickSemantics.LinkedInventory> targets = new ArrayList<>();
        List<ClickSemantics.LinkedInventory> linked = context.linkedInventories();
        for (int inventoryIndex = 0; inventoryIndex < linked.size(); inventoryIndex++) {
            ClickSemantics.LinkedInventory candidate = linked.get(inventoryIndex);
            if (candidate.inventory() != source && !candidate.inventory().frozen()) {
                targets.add(candidate);
            }
        }
        targets.sort((left, right) -> Integer.compare(
                right.inventory().operationPriority(OperationCategory.ADD),
                left.inventory().operationPriority(OperationCategory.ADD)
        ));
        return targets;
    }

    @NotNull
    private static PlannedRoot openPlan(SparrowInventory inventory, boolean write) {
        return write ? inventory.openPlanForWrite() : inventory.openPlan();
    }

    @NotNull
    private static InventoryAction actionOf(@Nullable ClickCandidate candidate) {
        return candidate == null ? InventoryAction.NOTHING : candidate.action();
    }

    // handled 说的是这一格归不归点击语义管, 和算不算得出候选无关.
    // 冻结槽就是个现成例子, 它归引擎管, 但永远算不出候选.
    record PreparedClick(
            boolean handled,
            @NotNull InventoryAction action,
            @Nullable ClickCandidate candidate
    ) {
    }
}
