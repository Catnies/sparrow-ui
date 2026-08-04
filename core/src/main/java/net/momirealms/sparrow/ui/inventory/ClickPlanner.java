package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * 点击与拖拽的规划器: 把一次交互算成精确候选, 不派发任何事件, 也不提交事务.
 * <p>{@code write} 为 {@code false} 时只用于预估 {@link InventoryAction}, 规划全程走只读快照.
 */
final class ClickPlanner {

    private ClickPlanner() {
    }

    @NotNull
    static PreparedClick prepareClick(
            ClickSemantics.Context context,
            ClickType clickType,
            int hotbarButton,
            int windowSlot,
            @Nullable ItemStack observedBundle,
            int selectedIndex,
            Runnable afterCommit,
            boolean write
    ) {
        if (windowSlot == InventoryView.OUTSIDE) {
            return new PreparedClick(false, ClickActions.outsideAction(context.cursor(), clickType), null);
        }
        // 冻结槽彻底不参与交互: 不算候选, 不派发任何事件, 也不分派 Item 点击, 只让客户端预测被纠正回来.
        if (context.frozenAt(windowSlot)) {
            return new PreparedClick(true, InventoryAction.NOTHING, null);
        }
        if (clickType == ClickType.UNKNOWN || clickType == ClickType.CREATIVE) {
            return new PreparedClick(context.linkAt(windowSlot) != null, InventoryAction.UNKNOWN, null);
        }

        ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
        if (link == null) {
            if (clickType != ClickType.DOUBLE_CLICK || context.cursor().isEmpty()) {
                return new PreparedClick(false, InventoryAction.NOTHING, null);
            }
            ClickCandidate collect = prepareCollect(context, null, write, new IdentityHashMap<>());
            return new PreparedClick(true, actionOf(collect), collect);
        }

        ClickCandidate candidate = switch (clickType) {
            case LEFT, RIGHT -> preparePickupOrPlace(
                    context,
                    link,
                    clickType,
                    observedBundle,
                    selectedIndex,
                    afterCommit,
                    write
            );
            case SHIFT_LEFT, SHIFT_RIGHT -> prepareShift(context, link, clickType, write);
            case NUMBER_KEY -> prepareHotbarSwap(context, link, hotbarButton, write);
            case SWAP_OFFHAND -> prepareOffhandSwap(context, link, write);
            case DROP, CONTROL_DROP -> prepareDrop(context, link, clickType == ClickType.CONTROL_DROP, write);
            case DOUBLE_CLICK -> prepareLinkedCollect(context, link, write);
            case MIDDLE -> prepareCreativeClone(context, link, write);
            default -> null;
        };
        InventoryAction action = clickType == ClickType.NUMBER_KEY && (hotbarButton < 0 || hotbarButton > 8)
                ? InventoryAction.UNKNOWN
                : actionOf(candidate);
        return new PreparedClick(true, action, candidate);
    }

    @Nullable
    private static ClickCandidate preparePickupOrPlace(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot link,
            ClickType clickType,
            @Nullable ItemStack observedBundle,
            int selectedIndex,
            Runnable afterCommit,
            boolean write
    ) {
        ItemStack cursor = context.cursor();
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), clickType, -1);
        SparrowInventory inventory = link.inventory();
        SparrowInventory.PlanContext plan = openPlan(inventory, write);
        @Nullable ItemStack current = plan.snapshot()[link.slot()];
        ClickSlotRules.Outcome outcome = clickType == ClickType.LEFT
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
        List<TransactionScope> scopes = plan.scoper().apply(List.of(
                new SlotChange(link.slot(), current, outcome.slotAfter())
        ));
        return ClickCandidate.of(
                action,
                link,
                reason,
                scopes,
                cursor,
                true,
                null,
                false,
                List.of(plan),
                false,
                InteractionDraft.cursorAfter(outcome.cursorAfter()),
                afterCommit
        );
    }

    @Nullable
    private static ItemStack placementInput(
            ClickType clickType,
            @Nullable ItemStack current,
            ItemStack cursor,
            ClickSlotRules.Outcome outcome
    ) {
        if (cursor.isEmpty()) {
            return null;
        }
        if (clickType == ClickType.LEFT && current != null && ClickActions.isBundle(cursor)) {
            return null;
        }
        if (clickType == ClickType.LEFT && ClickActions.isBundle(current)) {
            return null;
        }
        if (clickType == ClickType.RIGHT && current == null && ClickActions.isBundle(cursor)) {
            return outcome.placementInput();
        }
        return cursor;
    }

    @Nullable
    private static ClickCandidate prepareHotbarSwap(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot source,
            int hotbarButton,
            boolean write
    ) {
        if (hotbarButton < 0 || hotbarButton > 8) {
            return null;
        }
        ClickSemantics.LinkedSlot target = context.hotbarLink(hotbarButton);
        if (target == null || source.physicalKey().equals(target.physicalKey())) {
            return null;
        }

        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), ClickType.NUMBER_KEY, hotbarButton);
        SparrowInventory.PlanContext sourcePlan = openPlan(source.inventory(), write);
        SparrowInventory.PlanContext targetPlan = source.inventory() == target.inventory()
                ? sourcePlan
                : openPlan(target.inventory(), write);
        @Nullable ItemStack sourceItem = sourcePlan.snapshot()[source.slot()];
        @Nullable ItemStack targetItem = targetPlan.snapshot()[target.slot()];
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
            scopes = sourcePlan.scoper().apply(List.of(
                    new SlotChange(source.slot(), sourceItem, targetItem),
                    new SlotChange(target.slot(), targetItem, sourceItem)
            ));
        } else {
            scopes = new ArrayList<>(sourcePlan.scoper().apply(List.of(
                    new SlotChange(source.slot(), sourceItem, targetItem)
            )));
            scopes.addAll(targetPlan.scoper().apply(List.of(
                    new SlotChange(target.slot(), targetItem, sourceItem)
            )));
        }
        return ClickCandidate.of(
                InventoryAction.HOTBAR_SWAP,
                source,
                reason,
                scopes,
                context.cursor(),
                false,
                null,
                false,
                sourcePlan == targetPlan ? List.of(sourcePlan) : List.of(sourcePlan, targetPlan),
                false,
                InteractionDraft.empty(),
                () -> {}
        );
    }

    @Nullable
    private static ClickCandidate prepareOffhandSwap(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot source,
            boolean write
    ) {
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), ClickType.SWAP_OFFHAND, -1);
        SparrowInventory.PlanContext plan = openPlan(source.inventory(), write);
        @Nullable ItemStack current = plan.snapshot()[source.slot()];
        @Nullable ItemStack offhand = ItemUtils.nullIfEmpty(context.offhand());
        if (Objects.equals(current, offhand)) {
            return null;
        }
        if (!fitsReceiving(source, offhand) || !allowsPlacement(source, offhand)) {
            return null;
        }

        List<TransactionScope> scopes = plan.scoper().apply(List.of(
                new SlotChange(source.slot(), current, offhand)
        ));
        @Nullable ItemStack expectedOffhand = ItemUtils.copyOrNull(offhand);
        return ClickCandidate.of(
                InventoryAction.HOTBAR_SWAP,
                source,
                reason,
                scopes,
                context.cursor(),
                false,
                expectedOffhand,
                true,
                List.of(plan),
                false,
                InteractionDraft.offhandAfter(ItemUtils.copyOrNull(current)),
                () -> {}
        );
    }

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
            boolean write
    ) {
        ItemStack cursor = context.cursor();
        if (!cursor.isEmpty()) {
            return null;
        }
        ClickType clickType = fullStack ? ClickType.CONTROL_DROP : ClickType.DROP;
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), clickType, -1);
        SparrowInventory.PlanContext plan = openPlan(link.inventory(), write);
        @Nullable ItemStack current = plan.snapshot()[link.slot()];
        if (current == null) {
            return null;
        }
        int take = fullStack ? current.getAmount() : 1;
        int left = current.getAmount() - take;
        List<TransactionScope> scopes = plan.scoper().apply(List.of(new SlotChange(
                link.slot(),
                current,
                left > 0 ? ItemUtils.copyWithAmount(current, left) : null
        )));
        return ClickCandidate.of(
                fullStack ? InventoryAction.DROP_ALL_SLOT : InventoryAction.DROP_ONE_SLOT,
                link,
                reason,
                scopes,
                cursor,
                true,
                null,
                false,
                List.of(plan),
                false,
                InteractionDraft.dropped(ItemUtils.copyWithAmount(current, take)),
                () -> {}
        );
    }

    @Nullable
    private static ClickCandidate prepareCreativeClone(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot link,
            boolean write
    ) {
        ItemStack cursor = context.cursor();
        if (context.viewer().getGameMode() != GameMode.CREATIVE || !cursor.isEmpty()) {
            return null;
        }
        SparrowInventory.PlanContext plan = openPlan(link.inventory(), write);
        @Nullable ItemStack current = plan.snapshot()[link.slot()];
        if (current == null) {
            return null;
        }
        return ClickCandidate.of(
                InventoryAction.CLONE_STACK,
                link,
                new PlayerUpdateReason.Click(context.viewer(), ClickType.MIDDLE, -1),
                List.of(),
                cursor,
                true,
                null,
                false,
                List.of(plan),
                true,
                InteractionDraft.cursorAfter(ItemUtils.copyWithAmount(current, current.getMaxStackSize())),
                () -> {}
        );
    }

    @Nullable
    private static ClickCandidate prepareLinkedCollect(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot clicked,
            boolean write
    ) {
        ItemStack cursor = context.cursor();
        if (cursor.isEmpty()) {
            return null;
        }
        IdentityHashMap<SparrowInventory, SparrowInventory.PlanContext> plans = new IdentityHashMap<>();
        SparrowInventory.PlanContext clickedPlan = openPlan(clicked.inventory(), write);
        plans.put(clicked.inventory(), clickedPlan);
        if (clickedPlan.snapshot()[clicked.slot()] != null) {
            return null;
        }
        return prepareCollect(context, clicked, write, plans);
    }

    @Nullable
    private static ClickCandidate prepareCollect(
            ClickSemantics.Context context,
            @Nullable ClickSemantics.LinkedSlot clicked,
            boolean write,
            IdentityHashMap<SparrowInventory, SparrowInventory.PlanContext> plans
    ) {
        ItemStack cursor = context.cursor();
        int space = cursor.getMaxStackSize() - cursor.getAmount();
        if (space <= 0) {
            return null;
        }
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), ClickType.DOUBLE_CLICK, -1);
        int collected = 0;
        HashSet<SlotKey> coveredSlots = new HashSet<>();
        List<TransactionScope> scopes = new ArrayList<>();

        List<SparrowInventory> domain = new ArrayList<>(context.linkedInventories());
        domain.sort((left, right) -> Integer.compare(
                right.guiPriority(OperationCategory.COLLECT),
                left.guiPriority(OperationCategory.COLLECT)
        ));
        for (int inventoryIndex = 0; inventoryIndex < domain.size() && collected < space; inventoryIndex++) {
            SparrowInventory inventory = domain.get(inventoryIndex);
            SparrowInventory.PlanContext plan = plans.computeIfAbsent(inventory, key -> openPlan(key, write));
            InventoryPlanner.TakePlan takePlan = InventoryPlanner.planCollect(
                    plan.snapshot(),
                    cursor,
                    space - collected,
                    inventory.iterationOrder(OperationCategory.COLLECT),
                    slot -> coveredSlots.add(inventory.physicalKey(slot)),
                    inventory::slotMaxStackSize
            );
            scopes.addAll(plan.scoper().apply(takePlan.deltas()));
            collected += takePlan.taken();
        }
        if (collected <= 0) {
            return null;
        }

        int total = collected;
        return ClickCandidate.of(
                InventoryAction.COLLECT_TO_CURSOR,
                clicked,
                reason,
                scopes,
                cursor,
                true,
                null,
                false,
                new ArrayList<>(plans.values()),
                false,
                InteractionDraft.cursorAfter(ItemUtils.copyWithAmount(cursor, cursor.getAmount() + total)),
                () -> {}
        );
    }

    /**
     * 规划一次 shift 点击: 按 GUI 优先级依次尝试每个不共用源 RootInventory 的目标, 跨所有目标累积,
     * 直到源槽物品全部装完或目标全部试过为止. 这与原版一致 —— 一个目标装不下的剩余部分继续流向下一个目标,
     * 而不是在第一个有进展的目标处停下.
     * <p>每个被查询过的目标都进读集, 包括一件都没接下的目标: 目标是否装得下直接决定了后面的物品往哪走,
     * 一个当时满着的目标之后被清空, 本次分配就不再是应该产生的结果, 整个候选必须作废.
     * 因此这里的读集已经是最小集合, 不能按"有没有贡献"再收窄.
     *
     * @return 至少移动了一件物品时返回候选, 否则返回 {@code null}
     */
    @Nullable
    private static ClickCandidate prepareShift(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot source,
            ClickType clickType,
            boolean write
    ) {
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), clickType, -1);
        SparrowInventory.PlanContext sourcePlan = openPlan(source.inventory(), write);
        @Nullable ItemStack current = sourcePlan.snapshot()[source.slot()];
        if (current == null) {
            return null;
        }

        SlotKey.Anchor sourceAnchor = source.inventory().resolveSlot(source.slot());
        RootInventory sourceRoot = sourceAnchor.root();
        HashSet<SlotKey> coveredSlots = new HashSet<>();
        coveredSlots.add(source.physicalKey());
        List<TransactionScope> targetScopes = new ArrayList<>();
        List<SparrowInventory.PlanContext> readPlans = new ArrayList<>();
        readPlans.add(sourcePlan);
        int remaining = current.getAmount();

        List<SparrowInventory> targets = addTargets(context, sourceRoot);
        for (int targetIndex = 0; targetIndex < targets.size() && remaining > 0; targetIndex++) {
            SparrowInventory target = targets.get(targetIndex);
            SparrowInventory.PlanContext targetPlan = openPlan(target, write);
            readPlans.add(targetPlan);
            IntPredicate placement = target.placementPredicate(current);
            InventoryPlanner.AddPlan addPlan = InventoryPlanner.planAdd(
                    targetPlan.snapshot(),
                    ItemUtils.copyWithAmount(current, remaining),
                    target.iterationOrder(OperationCategory.ADD),
                    target::slotMaxStackSize,
                    slot -> coveredSlots.add(target.physicalKey(slot)) && placement.test(slot)
            );
            targetScopes.addAll(targetPlan.scoper().apply(addPlan.deltas()));
            remaining = addPlan.remaining();
        }
        int moved = current.getAmount() - remaining;
        if (moved <= 0) {
            return null;
        }

        List<TransactionScope> scopes = new ArrayList<>(sourcePlan.scoper().apply(List.of(new SlotChange(
                source.slot(),
                current,
                remaining > 0 ? ItemUtils.copyWithAmount(current, remaining) : null
        ))));
        scopes.addAll(targetScopes);
        return ClickCandidate.of(
                InventoryAction.MOVE_TO_OTHER_INVENTORY,
                source,
                reason,
                scopes,
                context.cursor(),
                false,
                null,
                false,
                readPlans,
                false,
                InteractionDraft.empty(),
                () -> {}
        );
    }

    @NotNull
    private static List<SparrowInventory> addTargets(
            ClickSemantics.Context context,
            RootInventory sourceRoot
    ) {
        List<SparrowInventory> targets = new ArrayList<>();
        List<SparrowInventory> linked = context.linkedInventories();
        for (int inventoryIndex = 0; inventoryIndex < linked.size(); inventoryIndex++) {
            SparrowInventory inventory = linked.get(inventoryIndex);
            if (!usesRoot(inventory, sourceRoot)) {
                targets.add(inventory);
            }
        }
        targets.sort((left, right) -> Integer.compare(
                right.guiPriority(OperationCategory.ADD),
                left.guiPriority(OperationCategory.ADD)
        ));
        return targets;
    }

    private static boolean usesRoot(SparrowInventory inventory, RootInventory root) {
        InventoryTopology topology = inventory.topology();
        for (int rootIndex = 0; rootIndex < topology.rootCount(); rootIndex++) {
            if (topology.rootAt(rootIndex) == root) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    static PreparedDrag prepareDrag(
            ClickSemantics.Context context,
            ClickType clickType,
            List<Integer> windowSlots
    ) {
        ItemStack cursor = context.cursor();
        boolean creative = clickType == ClickType.MIDDLE;
        if (cursor.isEmpty() || (creative && context.viewer().getGameMode() != GameMode.CREATIVE)) {
            return null;
        }

        // 约定: 拖拽只认背后有 Inventory 且未冻结的窗口槽, Item 槽, 空槽和冻结槽直接从候选里剔除.
        // 因此混合拖拽(一半 Item 槽一半 Inventory 槽)照常派发事件, 但 newItems 只有 Inventory 槽那一半,
        // 被剔除的槽位在插件视角里凭空消失; 整趟拖拽全落在这些槽上时候选为空, 什么事件都不派发.
        // 两者都是预期行为: 引擎接管不了的槽位没有分配结果可以呈现, 也没有事务可以取消.
        LinkedHashMap<SlotKey, DragLink> candidates = new LinkedHashMap<>();
        for (int windowIndex = 0; windowIndex < windowSlots.size(); windowIndex++) {
            int windowSlot = windowSlots.get(windowIndex);
            ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
            if (link != null && !context.frozenAt(windowSlot)) {
                candidates.putIfAbsent(link.physicalKey(), new DragLink(windowSlot, link));
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        List<ClickSemantics.LinkedSlot> reasonSlots = new ArrayList<>(candidates.size());
        for (DragLink candidate : candidates.values()) {
            reasonSlots.add(candidate.link());
        }
        UpdateReason reason = new PlayerUpdateReason.Drag(context.viewer(), clickType, reasonSlots);
        Map<SparrowInventory, SparrowInventory.PlanContext> plans = new LinkedHashMap<>();
        Map<SparrowInventory, IntPredicate> placements = new LinkedHashMap<>();
        List<DragTarget> targets = new ArrayList<>(candidates.size());
        for (DragLink candidate : candidates.values()) {
            ClickSemantics.LinkedSlot link = candidate.link();
            SparrowInventory inventory = link.inventory();
            SparrowInventory.PlanContext plan = plans.computeIfAbsent(inventory, key -> openPlan(key, true));
            @Nullable ItemStack current = plan.snapshot()[link.slot()];
            if (current != null && !ItemUtils.isSimilar(current, cursor)) {
                continue;
            }
            int capacity = effectiveCapacity(link, cursor) - ItemUtils.amountOf(current);
            if (capacity <= 0) {
                continue;
            }
            IntPredicate placement = placements.computeIfAbsent(
                    inventory,
                    key -> key.placementPredicate(cursor)
            );
            if (!placement.test(link.slot())) {
                continue;
            }
            targets.add(new DragTarget(candidate.windowSlot(), link, current, capacity));
        }
        if (targets.isEmpty()) {
            return null;
        }

        int perSlot = switch (clickType) {
            case LEFT -> cursor.getAmount() / targets.size();
            case RIGHT -> 1;
            default -> cursor.getMaxStackSize();
        };
        if (perSlot <= 0) {
            return null;
        }

        Map<SparrowInventory, List<SlotChange>> deltasByInventory = new LinkedHashMap<>();
        LinkedHashMap<Integer, ItemStack> newItems = new LinkedHashMap<>();
        int budget = creative ? Integer.MAX_VALUE : cursor.getAmount();
        int placedTotal = 0;
        for (int targetIndex = 0; targetIndex < targets.size() && budget > 0; targetIndex++) {
            DragTarget target = targets.get(targetIndex);
            int placed = Math.min(Math.min(perSlot, target.capacity()), budget);
            if (placed <= 0) {
                continue;
            }
            ItemStack after = ItemUtils.copyWithAmount(cursor, ItemUtils.amountOf(target.current()) + placed);
            deltasByInventory.computeIfAbsent(target.link().inventory(), inventory -> new ArrayList<>())
                    .add(new SlotChange(target.link().slot(), target.current(), after));
            newItems.put(target.windowSlot(), after.clone());
            if (!creative) {
                budget -= placed;
                placedTotal += placed;
            }
        }
        if (newItems.isEmpty()) {
            return null;
        }

        List<TransactionScope> scopes = new ArrayList<>();
        for (Map.Entry<SparrowInventory, List<SlotChange>> entry : deltasByInventory.entrySet()) {
            scopes.addAll(plans.get(entry.getKey()).scoper().apply(entry.getValue()));
        }
        ItemStack newCursor;
        if (creative) {
            newCursor = cursor.clone();
        } else {
            int left = cursor.getAmount() - placedTotal;
            newCursor = left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemStack.empty();
        }
        ClickCandidate candidate = ClickCandidate.of(
                InventoryAction.NOTHING,
                null,
                reason,
                scopes,
                cursor,
                true,
                null,
                false,
                new ArrayList<>(plans.values()),
                creative,
                InteractionDraft.cursorAfter(newCursor),
                () -> {}
        );
        return new PreparedDrag(candidate, newCursor, Map.copyOf(newItems));
    }

    @NotNull
    private static SparrowInventory.PlanContext openPlan(SparrowInventory inventory, boolean write) {
        return write ? inventory.openPlanForWrite() : inventory.openPlan();
    }

    @NotNull
    private static InventoryAction actionOf(@Nullable ClickCandidate candidate) {
        return candidate == null ? InventoryAction.NOTHING : candidate.action();
    }

    private static int effectiveCapacity(ClickSemantics.LinkedSlot link, ItemStack item) {
        return Math.min(link.inventory().slotMaxStackSize(link.slot()), item.getMaxStackSize());
    }

    record PreparedClick(
            boolean handled,
            @NotNull InventoryAction action,
            @Nullable ClickCandidate candidate
    ) {
    }

    record PreparedDrag(
            @NotNull ClickCandidate candidate,
            @NotNull ItemStack newCursor,
            @NotNull Map<Integer, ItemStack> newItems
    ) {
    }

    private record DragLink(
            int windowSlot,
            @NotNull ClickSemantics.LinkedSlot link
    ) {
    }

    private record DragTarget(
            int windowSlot,
            @NotNull ClickSemantics.LinkedSlot link,
            @Nullable ItemStack current,
            int capacity
    ) {
    }
}
