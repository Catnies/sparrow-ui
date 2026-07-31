package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ClickExecutor {
    private ClickExecutor() {
    }

    // 处理一次已经解析好的单击
    static boolean handleClick(
            @NotNull ClickSemantics.Context context,
            @NotNull ClickType clickType,
            int hotbarButton,
            int windowSlot,
            @Nullable ItemStack observedBundle,
            int selectedIndex,
            @NotNull Runnable afterCommit
    ) {
        ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);

        // Item 或 空槽: 只有双击收集与槽位无关.
        if (link == null) {
            if (clickType == ClickType.DOUBLE_CLICK && !context.cursor().isEmpty()) {
                collectToCursor(context);
                return true;
            }
            return false;
        }

        if (context.frozenAt(windowSlot)) {
            context.markDirty(windowSlot);
            return true;
        }

        switch (clickType) {
            case LEFT -> pickupOrPlace(context, link, windowSlot, ClickType.LEFT, null, -1, () -> {});
            case RIGHT -> pickupOrPlace(context, link, windowSlot, ClickType.RIGHT, observedBundle, selectedIndex, afterCommit);
            case SHIFT_LEFT, SHIFT_RIGHT -> shiftFromLink(context, link, windowSlot, clickType);
            case NUMBER_KEY -> swapWithHotbar(context, link, windowSlot, hotbarButton);
            case SWAP_OFFHAND -> swapWithOffhand(context, link, windowSlot);
            case DROP, CONTROL_DROP -> dropFromSlot(context, link, windowSlot, clickType == ClickType.CONTROL_DROP);
            case DOUBLE_CLICK -> {
                // 光标非空且被点槽为空时才收集
                if (!context.cursor().isEmpty() && link.inventory().itemAt(link.slot()) == null) {
                    collectToCursor(context);
                } else {
                    context.markDirty(windowSlot);
                }
            }
            case MIDDLE -> creativeClone(context, link, windowSlot);
            default -> context.markDirty(windowSlot);
        }
        return true;
    }

    // 处理一次已经完成的拖拽分配
    static void handleDrag(
            @NotNull ClickSemantics.Context context,
            @NotNull ClickType clickType,
            @NotNull List<Integer> windowSlots
    ) {
        ItemStack cursor = context.cursor();
        boolean creative = clickType == ClickType.MIDDLE;
        if (cursor.isEmpty() || (creative && context.viewer().getGameMode() != GameMode.CREATIVE)) {
            markAllDirty(context, windowSlots);
            return;
        }

        // 跨 InventoryLink 按最终物理槽去重
        LinkedHashMap<SlotKey, ClickSemantics.LinkedSlot> candidates = new LinkedHashMap<>();
        for (int i = 0; i < windowSlots.size(); i++) {
            int windowSlot = windowSlots.get(i);
            ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
            if (link == null) {
                continue;
            }
            if (context.frozenAt(windowSlot)) {
                continue;
            }

            candidates.putIfAbsent(link.physicalKey(), link);
        }

        // 按Inventory分组取得写规划快照, 读取全部发生在对账后的快照上
        Map<Inventory, SparrowInventory.PlanContext> plans = new LinkedHashMap<>();
        List<DragTarget> targets = new ArrayList<>(candidates.size());
        for (ClickSemantics.LinkedSlot link : candidates.values()) {
            SparrowInventory inventory = (SparrowInventory) link.inventory();
            SparrowInventory.PlanContext plan = plans.computeIfAbsent(inventory, key -> inventory.openPlanForWrite());
            if (!plan.writable(link.slot())) {
                continue;
            }
            @Nullable ItemStack current = plan.snapshot()[link.slot()];
            if (current != null && !ItemUtils.isSimilar(current, cursor)) {
                continue;
            }
            int capacity = effectiveCapacity(link, cursor) - ItemUtils.amountOf(current);
            if (capacity <= 0) {
                continue;
            }
            targets.add(new DragTarget(link, current, capacity));
        }
        if (targets.isEmpty()) {
            markAllDirty(context, windowSlots);
            return;
        }

        // 每槽配额: 左键均分, 右键每槽一个, 创造中键每槽整堆且不消耗光标
        int perSlot = switch (clickType) {
            case LEFT -> cursor.getAmount() / targets.size();
            case RIGHT -> 1;
            default -> cursor.getMaxStackSize();
        };
        if (perSlot <= 0) {
            markAllDirty(context, windowSlots);
            return;
        }

        // 逐槽计算实放量, delta 归入各自规划
        Map<Inventory, List<SlotDelta>> deltasByInventory = new LinkedHashMap<>();
        int budget = creative ? Integer.MAX_VALUE : cursor.getAmount();
        int placedTotal = 0;
        for (int i = 0; i < targets.size() && budget > 0; i++) {
            DragTarget target = targets.get(i);
            int placed = Math.min(Math.min(perSlot, target.capacity()), budget);
            if (placed <= 0) {
                continue;
            }
            ItemStack after = ItemUtils.copyWithAmount(cursor, ItemUtils.amountOf(target.current()) + placed);
            deltasByInventory.computeIfAbsent(target.link().inventory(), inventory -> new ArrayList<>())
                    .add(new SlotDelta(target.link().slot(), target.current(), after));
            if (!creative) {
                budget -= placed;
                placedTotal += placed;
            }
        }

        List<InventoryTransactions.Scope> scopes = new ArrayList<>();
        for (Map.Entry<Inventory, List<SlotDelta>> entry : deltasByInventory.entrySet()) {
            scopes.addAll(plans.get(entry.getKey()).scoper().apply(entry.getValue()));
        }
        TransactionResult result = InventoryTransactions.commit(
                new PlayerUpdateReason.Drag(context.viewer(), clickType, List.copyOf(candidates.values())),
                scopes,
                false
        );
        if (!(result instanceof TransactionResult.Committed)) {
            markAllDirty(context, windowSlots);
            return;
        }
        if (creative) {
            context.cursor(ItemStack.empty());
        } else {
            int left = cursor.getAmount() - placedTotal;
            context.cursor(left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemStack.empty());
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

    // 左右键的取放入口: 写规划 → 读取槽位 → 计算结果 → 提交 → 更新光标
    private static void pickupOrPlace(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot link,
            int windowSlot,
            ClickType clickType,
            @Nullable ItemStack observedBundle,
            int selectedIndex,
            Runnable afterCommit
    ) {
        ItemStack cursor = context.cursor();
        SparrowInventory inventory = (SparrowInventory) link.inventory();
        SparrowInventory.PlanContext plan = inventory.openPlanForWrite();
        if (!plan.writable(link.slot())) {
            context.markDirty(windowSlot);
            return;
        }
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
            context.markDirty(windowSlot);
            return;
        }

        TransactionResult result = InventoryTransactions.commit(
                new PlayerUpdateReason.Click(context.viewer(), clickType, -1),
                plan.scoper().apply(List.of(new SlotDelta(link.slot(), current, outcome.slotAfter()))),
                false
        );
        if (result instanceof TransactionResult.Committed) {
            context.cursor(outcome.cursorAfter());
            afterCommit.run();
        }
        context.markDirty(windowSlot);
    }

    // 数字键交换处理
    private static void swapWithHotbar(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot source,
            int windowSlot,
            int hotbarButton
    ) {
        ClickSemantics.LinkedSlot target = context.hotbarLink(hotbarButton);
        if (target == null) {
            context.markDirty(windowSlot);
            return;
        }
        if (source.physicalKey().equals(target.physicalKey())) {
            context.markDirty(windowSlot);
            return;
        }

        swapLinks(new PlayerUpdateReason.Click(context.viewer(), ClickType.NUMBER_KEY, hotbarButton), source, target);
        context.markDirty(windowSlot);
    }

    // 副手交换
    private static void swapWithOffhand(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot source,
            int windowSlot
    ) {
        SparrowInventory inventory = (SparrowInventory) source.inventory();
        SparrowInventory.PlanContext plan = inventory.openPlanForWrite();
        if (!plan.writable(source.slot())) {
            context.markDirty(windowSlot);
            return;
        }
        @Nullable ItemStack current = plan.snapshot()[source.slot()];
        @Nullable ItemStack offhand = context.offhand();
        if (current == null && offhand == null) {
            context.markDirty(windowSlot);
            return;
        }
        TransactionResult result = InventoryTransactions.commit(
                new PlayerUpdateReason.Click(context.viewer(), ClickType.SWAP_OFFHAND, -1),
                plan.scoper().apply(List.of(new SlotDelta(source.slot(), current, offhand))),
                false
        );
        if (result instanceof TransactionResult.Committed) {
            context.offhand(current);
        }
        context.markDirty(windowSlot);
    }

    // 把两个 Inventory 槽在一笔事务里整堆互换
    private static void swapLinks(
            UpdateReason reason,
            ClickSemantics.LinkedSlot source,
            ClickSemantics.LinkedSlot target
    ) {
        SparrowInventory sourceInventory = (SparrowInventory) source.inventory();
        SparrowInventory.PlanContext sourcePlan = sourceInventory.openPlanForWrite();
        if (!sourcePlan.writable(source.slot())) {
            return;
        }
        @Nullable ItemStack sourceItem = sourcePlan.snapshot()[source.slot()];

        if (source.inventory() == target.inventory()) {
            if (!sourcePlan.writable(target.slot())) {
                return;
            }
            @Nullable ItemStack targetItem = sourcePlan.snapshot()[target.slot()];
            if (sourceItem == null && targetItem == null) {
                return;
            }
            InventoryTransactions.commit(
                    reason,
                    sourcePlan.scoper().apply(List.of(
                            new SlotDelta(source.slot(), sourceItem, targetItem),
                            new SlotDelta(target.slot(), targetItem, sourceItem)
                    )),
                    false
            );
            return;
        }

        SparrowInventory targetInventory = (SparrowInventory) target.inventory();
        SparrowInventory.PlanContext targetPlan = targetInventory.openPlanForWrite();
        if (!targetPlan.writable(target.slot())) {
            return;
        }
        @Nullable ItemStack targetItem = targetPlan.snapshot()[target.slot()];
        if (sourceItem == null && targetItem == null) {
            return;
        }
        List<InventoryTransactions.Scope> scopes = new ArrayList<>(sourcePlan.scoper().apply(List.of(
                new SlotDelta(source.slot(), sourceItem, targetItem)
        )));
        scopes.addAll(targetPlan.scoper().apply(List.of(
                new SlotDelta(target.slot(), targetItem, sourceItem)
        )));
        InventoryTransactions.commit(reason, scopes, false);
    }

    // 从槽内丢出一个或整堆物品
    private static void dropFromSlot(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot link,
            int windowSlot,
            boolean fullStack
    ) {
        if (!context.cursor().isEmpty()) {
            context.markDirty(windowSlot);
            return;
        }

        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), fullStack ? ClickType.CONTROL_DROP : ClickType.DROP, -1);
        SparrowInventory inventory = (SparrowInventory) link.inventory();
        SparrowInventory.PlanContext plan = inventory.openPlanForWrite();
        if (!plan.writable(link.slot())) {
            context.markDirty(windowSlot);
            return;
        }
        @Nullable ItemStack current = plan.snapshot()[link.slot()];
        if (current == null) {
            context.markDirty(windowSlot);
            return;
        }
        int take = fullStack ? current.getAmount() : 1;
        int left = current.getAmount() - take;
        TransactionResult result = InventoryTransactions.commit(
                reason,
                plan.scoper().apply(List.of(new SlotDelta(link.slot(), current, left > 0 ? ItemUtils.copyWithAmount(current, left) : null))),
                false
        );
        if (result instanceof TransactionResult.Committed) {
            context.drop(ItemUtils.copyWithAmount(current, take));
        }
        context.markDirty(windowSlot);
    }

    // 创造模式中键复制
    private static void creativeClone(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot link,
            int windowSlot
    ) {
        @Nullable ItemStack current = link.inventory().itemAt(link.slot());
        if (context.viewer().getGameMode() != GameMode.CREATIVE || !context.cursor().isEmpty() || current == null) {
            context.markDirty(windowSlot);
            return;
        }
        context.cursor(ItemUtils.copyWithAmount(current, current.getMaxStackSize()));
        context.markDirty(windowSlot);
    }

    // 双击收集物品
    private static void collectToCursor(
            ClickSemantics.Context context
    ) {
        ItemStack cursor = context.cursor();
        int space = cursor.getMaxStackSize() - cursor.getAmount();
        if (space <= 0) {
            return;
        }
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), ClickType.DOUBLE_CLICK, -1);
        int collected = 0;
        HashSet<SlotKey> coveredSlots = new HashSet<>();
        List<InventoryTransactions.Scope> scopes = new ArrayList<>();

        List<Inventory> domain = new ArrayList<>(context.linkedInventories());
        domain.sort((left, right) -> Integer.compare(
                right.guiPriority(OperationCategory.COLLECT),
                left.guiPriority(OperationCategory.COLLECT)
        ));
        for (int i = 0; i < domain.size() && collected < space; i++) {
            SparrowInventory inventory = (SparrowInventory) domain.get(i);
            SparrowInventory.PlanContext plan = inventory.openPlanForWrite();
            InventoryPlanner.TakePlan takePlan = InventoryPlanner.planCollect(
                    plan.snapshot(),
                    cursor,
                    space - collected,
                    inventory.iterationOrder(OperationCategory.COLLECT),
                    slot -> plan.writable(slot) && coveredSlots.add(inventory.physicalKey(slot)),
                    inventory::slotMaxStackSize
            );
            scopes.addAll(plan.scoper().apply(takePlan.deltas()));
            collected += takePlan.taken();
        }
        if (!scopes.isEmpty() && !(InventoryTransactions.commit(reason, scopes, false) instanceof TransactionResult.Committed)) {
            return;
        }

        if (collected > 0) {
            context.cursor(ItemUtils.copyWithAmount(cursor, cursor.getAmount() + collected));
        }
    }

    // Shift 快速转移物品
    private static void shiftFromLink(
            ClickSemantics.Context context,
            ClickSemantics.LinkedSlot link,
            int windowSlot,
            ClickType clickType
    ) {
        // 快速空判可基于滞后镜像, 真正的读取在各目标的事务窗口内完成
        if (link.inventory().itemAt(link.slot()) == null) {
            context.markDirty(windowSlot);
            return;
        }
        UpdateReason reason = new PlayerUpdateReason.Click(context.viewer(), clickType, -1);
        SlotKey sourceKey = link.physicalKey();

        List<Inventory> targets = addTargets(context, link.inventory());
        for (int i = 0; i < targets.size(); i++) {
            MoveOutcome outcome = moveIntoInventory(reason, link, targets.get(i), sourceKey);
            if (outcome == MoveOutcome.MOVED || outcome == MoveOutcome.REJECTED) {
                // 有进展即停; 事务被取消或冲突同样终止本次转移
                context.markDirty(windowSlot);
                return;
            }
        }
        context.markDirty(windowSlot);
    }

    // 尝试把源槽的物品堆转移进一个目标 Inventory.
    private static MoveOutcome moveIntoInventory(
            UpdateReason reason,
            ClickSemantics.LinkedSlot source,
            Inventory target,
            SlotKey sourceKey
    ) {
        SparrowInventory sourceInventory = (SparrowInventory) source.inventory();
        SparrowInventory targetInventory = (SparrowInventory) target;

        SparrowInventory.PlanContext sourcePlan = sourceInventory.openPlanForWrite();
        SparrowInventory.PlanContext targetPlan = targetInventory.openPlanForWrite();
        if (!sourcePlan.writable(source.slot())) {
            return MoveOutcome.REJECTED;
        }
        @Nullable ItemStack current = sourcePlan.snapshot()[source.slot()];
        if (current == null) {
            return MoveOutcome.FULL;
        }
        // 上限报 0 的槽在规划里等于隐身: 不可写的槽, 以及与源槽是同一物理槽的目标槽
        InventoryPlanner.AddPlan addPlan = InventoryPlanner.planAdd(
                targetPlan.snapshot(),
                current,
                targetInventory.iterationOrder(OperationCategory.ADD),
                slot -> !targetPlan.writable(slot) || targetInventory.physicalKey(slot).equals(sourceKey)
                        ? 0
                        : targetInventory.slotMaxStackSize(slot)
        );
        int moved = current.getAmount() - addPlan.remaining();
        if (moved <= 0) {
            return MoveOutcome.FULL;
        }

        int left = current.getAmount() - moved;
        List<SlotDelta> sourceDeltas = List.of(new SlotDelta(
                source.slot(),
                current,
                left > 0 ? ItemUtils.copyWithAmount(current, left) : null
        ));
        List<InventoryTransactions.Scope> scopes = new ArrayList<>(sourcePlan.scoper().apply(sourceDeltas));
        scopes.addAll(targetPlan.scoper().apply(addPlan.deltas()));
        TransactionResult result = InventoryTransactions.commit(reason, scopes, false);
        if (result instanceof TransactionResult.Committed) {
            return MoveOutcome.MOVED;
        }
        return result == TransactionResult.Unavailable.INSTANCE ? MoveOutcome.FULL : MoveOutcome.REJECTED;
    }

    // 快速转移的候选目标
    private static List<Inventory> addTargets(ClickSemantics.Context context, @Nullable Inventory exclude) {
        List<Inventory> targets = new ArrayList<>(context.linkedInventories());
        if (exclude != null) {
            targets.remove(exclude);
        }
        targets.sort((left, right) -> Integer.compare(
                right.guiPriority(OperationCategory.ADD),
                left.guiPriority(OperationCategory.ADD)
        ));
        return targets;
    }

    private static int effectiveCapacity(ClickSemantics.LinkedSlot link, ItemStack item) {
        return Math.min(link.inventory().slotMaxStackSize(link.slot()), item.getMaxStackSize());
    }

    private static void markAllDirty(ClickSemantics.Context context, List<Integer> windowSlots) {
        for (int i = 0; i < windowSlots.size(); i++) {
            context.markDirty(windowSlots.get(i));
        }
    }

    // 单次目标 Inventory 转移的结果.
    private enum MoveOutcome {
        MOVED,
        FULL,
        REJECTED
    }

    // 拖拽分配中一个还能接收物品的槽位.
    private record DragTarget(
            @NotNull ClickSemantics.LinkedSlot link,
            @Nullable ItemStack current,
            int capacity
    ) {
    }
}
