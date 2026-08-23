package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.InventoryPlanner;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.click.rules.ClickActions;
import net.momirealms.sparrow.ui.inventory.click.rules.ClickBundleRules;
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
import java.util.function.IntPredicate;

// 将当前 Window 状态与事件覆盖计算成可校验的交互候选.
final class ClickPlanner {

    // handled 表示语义归属, 候选为空仍可能由引擎接管.
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
        // 未知点击保持 UNKNOWN, 冻结只影响事件闸门.
        if (clickType == ClickType.UNKNOWN || clickType == ClickType.CREATIVE) {
            return new PreparedClick(context.frozenAt(windowSlot) || context.linkAt(windowSlot) != null, InventoryAction.UNKNOWN, null);
        }
        // 冻结槽由引擎接管, 只纠正客户端预测.
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

        ItemStack incoming = placementInput(clickType, current, cursor, outcome);
        if (incoming != null && !inventory.placementPredicate(incoming).test(link.slot())) {
            return null;
        }

        InventoryAction action = clickType == ClickType.LEFT
                ? ClickActions.leftAction(current, cursor, outcome)
                : ClickActions.rightAction(current, cursor);
        List<TransactionScope> scopes = List.of(new TransactionScope(plan, List.of(
                new SlotChange(link.slot(), current, outcome.slotAfter())
        )));
        return ClickCandidate.plan(action, reason)
                .eventTarget(link)
                .scopes(scopes)
                .reads(List.of(plan))
                .checkCursor(actualCursor)
                .draft(InteractionDraft.cursorAfter(outcome.cursorAfter()))
                .afterCommit(afterCommit)
                .build();
    }

    // Bundle 取出时校验袋内物品, 放入 Bundle 不触发槽位放入规则.
    @Nullable
    private static ItemStack placementInput(
            ClickType clickType,
            @Nullable ItemStack current,
            ItemStack cursor,
            ClickOutcome outcome
    ) {
        if (cursor.isEmpty()) {
            return null;
        }
        if (clickType == ClickType.LEFT && current != null && ClickBundleRules.isBundle(cursor)) {
            return null;
        }
        if (clickType == ClickType.LEFT && ClickBundleRules.isBundle(current)) {
            return null;
        }
        if (clickType == ClickType.RIGHT && current == null && ClickBundleRules.isBundle(cursor)) {
            return outcome.placementInput();
        }
        return cursor;
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
        if (!allowsPlacement(source, targetItem) || !allowsPlacement(target, sourceItem)) {
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
        if (!fitsReceiving(source, offhand) || !allowsPlacement(source, offhand)) {
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

    // 交换保持整堆, 接收槽不拆分超限物品.
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

    private static boolean allowsPlacement(ClickSemantics.LinkedSlot target, @Nullable ItemStack incoming) {
        return incoming == null || target.inventory().placementPredicate(incoming).test(target.slot());
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
        if (!overlay.cursorOr(actualCursor).isEmpty()) {
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
        List<TransactionScope> scopes = List.of(new TransactionScope(plan, List.of(new SlotChange(
                link.slot(),
                current,
                left > 0 ? ItemUtils.copyWithAmount(current, left) : null
        ))));
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
        if (context.viewer().getGameMode() != GameMode.CREATIVE || !overlay.cursorOr(actualCursor).isEmpty()) {
            return null;
        }
        PlannedRoot plan = openPlan(link.inventory(), write);
        @Nullable ItemStack current = overlay.viewOf(plan)[link.slot()];
        if (current == null) {
            return null;
        }
        return ClickCandidate.plan(InventoryAction.CLONE_STACK, new PlayerUpdateReason.Click(context.viewer(), ClickType.MIDDLE, -1))
                .eventTarget(link)
                .reads(List.of(plan))
                .checkCursor(actualCursor)
                .requireCreative(true)
                .draft(InteractionDraft.cursorAfter(ItemUtils.copyWithAmount(current, current.getMaxStackSize())))
                .build();
    }

    // 双击的第二个包只在被点槽显示为空且真实内容也为空时执行收集.
    @Nullable
    private static ClickCandidate prepareLinkedCollect(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot clicked,
            int windowSlot,
            boolean write,
            InteractionOverlay overlay
    ) {
        if (overlay.cursorOr(context.cursor()).isEmpty() || !context.displayedEmptyAt(windowSlot)) {
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
                    // 先判断可见性, 再认领可能由多个 Inventory 暴露的物理槽.
                    slot -> linked.visible(slot) && coveredSlots.add(inventory.physicalKey(slot)),
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

    // 每个检查过的 shift 目标都会影响后续分配, <strong>即使没有接收物品也必须进入读集</strong>.
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
            IntPredicate placement = target.placementPredicate(current);
            InventoryPlanner.AddPlan addPlan = InventoryPlanner.planAdd(
                    overlay.viewOf(targetPlan),
                    ItemUtils.copyWithAmount(current, remaining),
                    target.iterationOrder(OperationCategory.ADD),
                    target::slotMaxStackSize,
                    // 先判断可见性, 再认领物理槽.
                    slot -> linked.visible(slot) && coveredSlots.add(target.physicalKey(slot)) && placement.test(slot)
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

        List<TransactionScope> scopes = new ArrayList<>(targetScopes.size() + 1);
        scopes.add(new TransactionScope(sourcePlan, List.of(new SlotChange(
                source.slot(),
                current,
                remaining > 0 ? ItemUtils.copyWithAmount(current, remaining) : null
        ))));
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

    // handled 说的是这一格归不归点击语义管, 与算不算得出候选无关, 冻结槽归引擎管却永远没有候选.
    record PreparedClick(
            boolean handled,
            @NotNull InventoryAction action,
            @Nullable ClickCandidate candidate
    ) {
    }
}
