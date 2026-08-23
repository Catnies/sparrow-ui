package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.storage.SlotKey;
import net.momirealms.sparrow.ui.inventory.transaction.InteractionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

final class DragPlanner {

    // 把一趟拖拽算成实际分配候选, Bukkit 事件看到的 newItems 与随后提交的候选完全一致.
    @Nullable
    static PreparedDrag prepare(
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

        // 只保留可交互的 Inventory 槽位, 并按物理身份去重.
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
            PlannedRoot plan = plans.computeIfAbsent(inventory, SparrowInventory::openPlanForWrite);
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

        // 左键均分, 右键每格一个, 创造模式中键填满且不消耗光标.
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
            newItems.put(target.windowSlot(), after);
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
        return new PreparedDrag(candidate.withRealBefore(overlay), newCursor, Map.copyOf(newItems));
    }

    private static int effectiveCapacity(ClickSemantics.LinkedSlot link, ItemStack item) {
        return Math.min(link.inventory().slotMaxStackSize(link.slot()), item.getMaxStackSize());
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
