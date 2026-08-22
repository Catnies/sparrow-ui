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

// 拖拽的规划器, 输入是手势经过的一串窗口槽, 输出是把光标按键位分摊下去的候选.
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

        // 拖拽只认背后有 Inventory 且未冻结的窗口槽, Item 槽, 空槽和冻结槽直接从候选里剔除.
        // 因此混合拖拽(一半 Item 槽一半 Inventory 槽)照常派发事件, 但 newItems 只有 Inventory 槽那一半,
        // 被剔除的槽位在插件视角里凭空消失; 整趟拖拽全落在这些槽上时候选为空, 不派发 Bukkit 事件.
        // 两者都是预期行为, 引擎接管不了的槽位没有分配结果可以呈现, 也没有事务可以取消.
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
            newItems.put(target.windowSlot(), after); // 前面复制过了, 不用再复制.
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
