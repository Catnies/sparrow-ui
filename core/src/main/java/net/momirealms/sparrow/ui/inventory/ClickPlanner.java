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
 * 把一次交互算成精确候选.
 * <p>{@code write} 为 {@code false} 时只用于预估 {@link InventoryAction}, 规划全程走只读快照.
 * <p>规划读到的现场由 {@link InteractionOverlay} 决定, 首次规划时覆盖层是空的, 读的就是 Inventory
 * 的规划基准, Bukkit 闸门之后的重规划则读叠加了事件写入的现场.
 */
final class ClickPlanner {

    // 把一次单击算成候选. handled 说明这一格归不归引擎管, 与算不算得出候选是两回事.
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
        // 无法归类的点击对外恒报 UNKNOWN, 冻结与否改变不了"看不懂"这个事实; 事件闸门仍由执行器按冻结拦截,
        // 冻结槽保持已接管, Item 分派同样不放行.
        if (clickType == ClickType.UNKNOWN || clickType == ClickType.CREATIVE) {
            return new PreparedClick(context.frozenAt(windowSlot) || context.linkAt(windowSlot) != null, InventoryAction.UNKNOWN, null);
        }
        // 冻结槽彻底不参与交互: 不算候选, 不派发任何事件, 也不分派 Item 点击, 只让客户端预测被纠正回来.
        if (context.frozenAt(windowSlot)) {
            return new PreparedClick(true, InventoryAction.NOTHING, null);
        }

        ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
        // 背后没有 Inventory 的槽位一件真实物品都拿不出来, 双击收集要求被点的槽有物品可拿, 这里一律不成立.
        if (link == null) {
            return new PreparedClick(false, InventoryAction.NOTHING, null);
        }
        // Inventory 级冻结是玩家侧只读: 点在冻结 Inventory 展示槽上的一切动作与冻结槽同待遇.
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
        return new PreparedClick(true, action, withRealBefore(candidate, overlay));
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

    // 挑出这次真正落进槽位, 该拿去过放入规则的那件东西. 光标拿着收纳袋右键空槽时, 进槽的是袋子里掏出来的那一件,
    // 反过来往袋子里塞东西不算放进这一格, 一律返回 null 表示不必过规则.
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
        // 快捷栏端也可能落在冻结的 Inventory 上, 玩家侧只读时两端谁冻结都不成立.
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

    // 交换是整堆搬过去, 接收端一格装不下就不成立, 不会替玩家拆堆.
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

    /**
     * 规划一次双击收集.
     * <p>客户端的双击发的是 PICKUP 与 PICKUP_ALL 两个包, 第一个包拿起被点槽的物品, 第二个包才收集同类.
     * 因此收集成立时被点的槽恰好是空的, 而玩家握着物品去双击时第一个包会把物品放回去, 那一格不再为空.
     *
     * @return 被点的槽拿得出收集资格时返回候选, 否则返回 {@code null}
     */
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
            // 玩家侧只读的 Inventory 不作为收集来源, 也不进读集.
            if (inventory.frozen()) {
                continue;
            }
            PlannedRoot plan = plans.computeIfAbsent(inventory, key -> openPlan(key, write));
            InventoryPlanner.TakePlan takePlan = InventoryPlanner.planCollect(
                    overlay.viewOf(plan),
                    cursor,
                    space - collected,
                    inventory.iterationOrder(OperationCategory.COLLECT),
                    // 可见性在读集去重前短路: 不可见槽不得占用 coveredSlots, 同一物理槽可能经另一个 Inventory 可见暴露
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

    // 规划一次 shift 点击, 按优先级挨个试每个不是源 Inventory 的目标, 跨目标累积, 直到源槽物品全部装完或目标试完.
    // 每个问过的目标都要进读集, 目标装不装得下直接决定了后面的物品往哪走, 一个当时满着的目标之后被清空,
    // 本次分配就不再是应该产生的结果, 整个候选必须作废. 所以不能按"有没有贡献"再收窄.
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
                    // 可见性在读集去重前短路: 不可见槽不得占用 coveredSlots, 同一物理槽可能经另一个 Inventory 可见暴露
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
            // 玩家侧只读的 Inventory 不作为快速转移目标.
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

    // 把一趟拖拽算成实际分配候选, Bukkit 事件看到的 newItems 与随后提交的候选完全一致.
    @Nullable
    static PreparedDrag prepareDrag(
            ClickSemantics.Context context,
            ClickType clickType,
            List<Integer> windowSlots,
            InteractionOverlay overlay
    ) {
        ItemStack actualCursor = context.cursor();
        ItemStack cursor = overlay.cursorOr(actualCursor);
        boolean creative = clickType == ClickType.MIDDLE;
        if (cursor.isEmpty() || (creative && context.viewer().getGameMode() != GameMode.CREATIVE)) {
            return null;
        }

        // 约定: 拖拽只认背后有 Inventory 且未冻结的窗口槽, Item 槽, 空槽和冻结槽直接从候选里剔除.
        // 因此混合拖拽(一半 Item 槽一半 Inventory 槽)照常派发事件, 但 newItems 只有 Inventory 槽那一半,
        // 被剔除的槽位在插件视角里凭空消失; 整趟拖拽全落在这些槽上时候选为空, 不派发 Bukkit 事件.
        // 两者都是预期行为: 引擎接管不了的槽位没有分配结果可以呈现, 也没有事务可以取消.
        LinkedHashMap<SlotKey, DragLink> candidates = new LinkedHashMap<>();
        for (int windowIndex = 0; windowIndex < windowSlots.size(); windowIndex++) {
            int windowSlot = windowSlots.get(windowIndex);
            ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
            if (link != null && !context.frozenAt(windowSlot) && !link.inventory().frozen()) {
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
        Map<SparrowInventory, PlannedRoot> plans = new LinkedHashMap<>();
        Map<SparrowInventory, IntPredicate> placements = new LinkedHashMap<>();
        List<DragTarget> targets = new ArrayList<>(candidates.size());
        for (DragLink candidate : candidates.values()) {
            ClickSemantics.LinkedSlot link = candidate.link();
            SparrowInventory inventory = link.inventory();
            PlannedRoot plan = plans.computeIfAbsent(inventory, key -> openPlan(key, true));
            @Nullable ItemStack current = overlay.viewOf(plan)[link.slot()];
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

        // 左键均分, 右键每格一个, 创造模式中键每格塞满且不消耗光标
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
            scopes.add(new TransactionScope(plans.get(entry.getKey()), entry.getValue()));
        }
        ItemStack newCursor;
        if (creative) {
            newCursor = cursor.clone();
        } else {
            int left = cursor.getAmount() - placedTotal;
            newCursor = left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemUtils.EMPTY;
        }
        ClickCandidate candidate = ClickCandidate.plan(InventoryAction.NOTHING, reason)
                .scopes(scopes)
                .reads(new ArrayList<>(plans.values()))
                .checkCursor(actualCursor)
                .requireCreative(creative)
                .draft(InteractionDraft.cursorAfter(newCursor))
                .build();
        return new PreparedDrag(withRealBefore(candidate, overlay), newCursor, Map.copyOf(newItems));
    }

    // 覆盖层只改变规划期读到的现场, 不改变容器里的真账. 写集记下的 before 因此换回规划基准的真实内容,
    // 让 Pre, Post 处理器和净变化统计看到这一格实际从什么变成什么, 而不是一份容器从来没有过的账.
    // 提交只用 after, 并发校验只比对基准数组本身, 所以这一步不影响事务本身的结果.
    @Nullable
    private static ClickCandidate withRealBefore(@Nullable ClickCandidate candidate, InteractionOverlay overlay) {
        if (candidate == null || overlay.isEmpty() || candidate.scopes().isEmpty()) {
            return candidate;
        }
        List<TransactionScope> scopes = candidate.scopes();
        List<TransactionScope> rewritten = new ArrayList<>(scopes.size());
        for (int scopeIndex = 0; scopeIndex < scopes.size(); scopeIndex++) {
            TransactionScope scope = scopes.get(scopeIndex);
            @Nullable ItemStack[] planned = scope.planned();
            List<SlotChange> changes = scope.slotChanges();
            List<SlotChange> restored = new ArrayList<>(changes.size());
            for (int changeIndex = 0; changeIndex < changes.size(); changeIndex++) {
                SlotChange change = changes.get(changeIndex);
                // 只换 before 的来源, after 沿用候选算出的内容.
                restored.add(new SlotChange(change.slot(), planned[change.slot()], change.unsafeAfter()));
            }
            rewritten.add(scope.withSlotChanges(restored));
        }
        return new ClickCandidate(
                candidate.action(),
                candidate.eventTarget(),
                candidate.reason(),
                List.copyOf(rewritten),
                candidate.plannedRoots(),
                candidate.expectedCursor(),
                candidate.checkCursor(),
                candidate.expectedOffhand(),
                candidate.checkOffhand(),
                candidate.requireCreative(),
                candidate.draft(),
                candidate.afterCommit()
        );
    }

    @NotNull
    private static PlannedRoot openPlan(SparrowInventory inventory, boolean write) {
        return write ? inventory.openPlanForWrite() : inventory.openPlan();
    }

    @NotNull
    private static InventoryAction actionOf(@Nullable ClickCandidate candidate) {
        return candidate == null ? InventoryAction.NOTHING : candidate.action();
    }

    private static int effectiveCapacity(ClickSemantics.LinkedSlot link, ItemStack item) {
        return Math.min(link.inventory().slotMaxStackSize(link.slot()), item.getMaxStackSize());
    }

    // handled 说的是这一格归不归点击语义管, 与算不算得出候选无关, 冻结槽归引擎管却永远没有候选.
    record PreparedClick(
            boolean handled,
            @NotNull InventoryAction action,
            @Nullable ClickCandidate candidate
    ) {
    }

    // newCursor 与 newItems 是给 Bukkit 拖拽事件看的最终分配结果, 与 candidate 出自同一次计算.
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

    // capacity 是这一格还能再吃下多少件, 已经扣掉了槽里现有的数量.
    private record DragTarget(
            int windowSlot,
            @NotNull ClickSemantics.LinkedSlot link,
            @Nullable ItemStack current,
            int capacity
    ) {
    }
}
