package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Window 点击翻译为库存事务与玩家侧变更的语义引擎.
 * <p>每种点击是一个事务: 库存侧变更(可跨多个根库存)经事务管线提交, 全成全败;
 * 光标与副手是玩家实体线程私有状态, 在事务提交成功后无条件应用, 永不失败,
 * 因此不存在半点击. 事务被 pre 观察者取消或乐观校验冲突时, 本次点击零变更,
 * 涉及槽位被标脏以纠正客户端预测.
 * <p>库存槽的读取一律发生在 {@code openPlanForWrite} 之后的规划快照上 —— 读取
 * 与乐观校验基准同源, 镜像型库存的对账先于读取完成, 任何插入写(外部容器变更,
 * 异步写者)都会使提交降级为 Conflicted 而不是基于陈旧值的覆盖.
 * <p>代理菜单没有任何原版 Slot, 点击包也不会到达 NMS 逻辑; default lower 通过
 * viewer storage reference 接入本引擎, 因而纯 lower 操作同样进入库存事务.
 * <p>调用方(Window 层)通过 {@link Context} 提供槽位路由与玩家侧 IO,
 * 本类不持有任何状态, 全部方法在玩家实体线程调用.
 */
public final class ClickSemantics {

    /**
     * Window 层提供的交互上下文.
     * <p>读取方法返回可自由持有的快照; 写入方法是权威覆盖, 实现方负责把变更
     * 同步给客户端(标脏, 光标脏位等).
     */
    public interface Context {

        /**
         * 交互的玩家.
         */
        @NotNull
        Player viewer();

        /**
         * 返回窗口槽位连接的 Inventory 槽位, Item 或空槽返回 {@code null}.
         */
        @Nullable
        LinkedSlot linkAt(int windowSlot);

        /**
         * 返回窗口槽位的显示路径是否被冻结; 冻结槽不参与任何语义.
         */
        boolean frozenAt(int windowSlot);

        /**
         * 返回当前 lower 快捷栏位置可交互的实际库存连接; 非库存元素或冻结路径返回 {@code null}.
         */
        @Nullable
        LinkedSlot hotbarLink(int hotbarButton);

        /**
         * 按显示顺序返回参与语义的全部去重库存, 用于快速转移与收集的目标域;
         * 实现方应排除仅经冻结或协议外槽位连接的库存.
         */
        @NotNull
        List<Inventory> linkedInventories();

        /**
         * 真实光标物品的快照; 空光标返回空物品而不是 {@code null}.
         */
        @NotNull
        ItemStack cursor();

        /**
         * 权威覆盖真实光标.
         */
        void cursor(@NotNull ItemStack cursor);

        /**
         * 副手物品快照.
         */
        @Nullable
        ItemStack offhand();

        /**
         * 权威覆盖副手.
         */
        void offhand(@Nullable ItemStack item);

        /**
         * 以玩家名义把物品丢入世界.
         */
        void drop(@NotNull ItemStack item);

        /**
         * 标记窗口槽位需要复核, 下一次同步以权威状态纠正客户端预测.
         */
        void markDirty(int windowSlot);
    }

    /**
     * 窗口槽位连接的库存槽.
     */
    public record LinkedSlot(@NotNull Inventory inventory, int slot) {
    }

    private ClickSemantics() {
    }

    /**
     * 处理一次已解释的单击.
     *
     * @return 语义已接管时返回 {@code true}; 返回 {@code false} 表示该槽不属于
     * 库存语义(Item 或空槽), 调用方按原有 Item 分派处理
     */
    public static boolean handleClick(@NotNull Context context, @NotNull ClickType clickType, int hotbarButton, int windowSlot) {
        LinkedSlot link = context.linkAt(windowSlot);

        if (link == null) {
            // Item 或空槽: 只有双击收集与槽位无关, 其余交回 Item 分派
            // todo 何意味我怎么没看懂, 双击正常Item怎么还触发收集?
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
            case LEFT -> pickupOrPlace(context, link, windowSlot, ClickType.LEFT);
            case RIGHT -> pickupOrPlace(context, link, windowSlot, ClickType.RIGHT);
            case SHIFT_LEFT, SHIFT_RIGHT -> shiftFromLink(context, link, windowSlot, clickType);
            case NUMBER_KEY -> swapWithHotbar(context, link, windowSlot, hotbarButton);
            case SWAP_OFFHAND -> swapWithOffhand(context, link, windowSlot);
            case DROP, CONTROL_DROP -> dropFromSlot(context, link, windowSlot, clickType == ClickType.CONTROL_DROP);
            case DOUBLE_CLICK -> {
                // 原版语义: 光标非空且被点槽为空时才收集
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

    /**
     * 处理容器外点击: 光标非空时按原版语义丢出(左键全堆, 右键一个).
     * 先清空光标再丢出, 与 Paper 的防复制加固顺序一致 —— 掉落事件的处理器
     * 若重入关闭窗口, 归还路径读到的光标已是扣减后的值.
     */
    public static void handleOutsideClick(@NotNull Context context, @NotNull ClickType clickType) {
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

    /**
     * 处理一次已完成的拖拽分配: 全部参与库存槽进入同一个事务, 余量回到光标.
     * 显示同一物理槽的多个窗口槽只参与一次.
     */
    public static void handleDrag(@NotNull Context context, @NotNull ClickType clickType, @NotNull List<Integer> windowSlots) {
        ItemStack cursor = context.cursor();
        boolean creative = clickType == ClickType.MIDDLE;
        if (cursor.isEmpty() || (creative && context.viewer().getGameMode() != GameMode.CREATIVE)) {
            markAllDirty(context, windowSlots);
            return;
        }

        // 阶段一: 跨 InventoryLink 按最终物理槽去重
        LinkedHashMap<SparrowInventory.SlotKey, LinkedSlot> candidates = new LinkedHashMap<>();
        for (int i = 0; i < windowSlots.size(); i++) {
            int windowSlot = windowSlots.get(i);
            LinkedSlot link = context.linkAt(windowSlot);
            if (link == null) {
                continue;
            }
            if (context.frozenAt(windowSlot)) {
                continue;
            }

            candidates.putIfAbsent(physicalKey(link), link);
        }

        // 阶段二: 按库存分组取得写规划快照 —— 读取全部发生在对账后的快照上
        Map<Inventory, SparrowInventory.PlanContext> plans = new LinkedHashMap<>();
        List<DragTarget> targets = new ArrayList<>(candidates.size());
        for (LinkedSlot link : candidates.values()) {
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

        // 阶段三: 逐槽计算实放量, delta 归入各自规划
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
                new UpdateReason.PlayerDrag(context.viewer(), clickType),
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

    // ---- 单槽: 拾起, 放入, 合并与交换 ----

    // 左右键的取放语义: 库存槽经规划快照读取并提交事务
    private static void pickupOrPlace(Context context, LinkedSlot link, int windowSlot, ClickType clickType) {
        ItemStack cursor = context.cursor();
        applyLinkSemantics(context, reasonOf(context, clickType), link, windowSlot, (current, slotLimit) ->
                clickType == ClickType.LEFT
                        ? computeLeftClick(current, cursor, slotLimit)
                        : computeRightClick(current, cursor, slotLimit));
    }

    // 左键: 拾起整堆 / 放入尽可能多 / 相似合并 / 不相似整堆交换(受槽上限门约束)
    @Nullable
    private static SlotOutcome computeLeftClick(@Nullable ItemStack current, ItemStack cursor, int slotLimit) {
        if (cursor.isEmpty()) {
            return current == null ? null : new SlotOutcome(null, current);
        }
        if (current == null) {
            int placeable = Math.min(effectiveLimit(slotLimit, cursor), cursor.getAmount());
            if (placeable <= 0) {
                return null;
            }
            return new SlotOutcome(ItemUtils.copyWithAmount(cursor, placeable), remainderOf(cursor, placeable));
        }
        if (ItemUtils.isSimilar(current, cursor)) {
            int space = effectiveLimit(slotLimit, current) - current.getAmount();
            int moved = Math.clamp(space, 0, cursor.getAmount());
            if (moved == 0) {
                return null;
            }
            return new SlotOutcome(ItemUtils.copyWithAmount(current, current.getAmount() + moved), remainderOf(cursor, moved));
        }
        return computeSwap(current, cursor, slotLimit);
    }

    // 右键: 拾起一半(向上取整) / 放一个 / 不相似整堆交换
    @Nullable
    private static SlotOutcome computeRightClick(@Nullable ItemStack current, ItemStack cursor, int slotLimit) {
        if (cursor.isEmpty()) {
            if (current == null) {
                return null;
            }
            int take = (current.getAmount() + 1) / 2;
            int left = current.getAmount() - take;
            return new SlotOutcome(left > 0 ? ItemUtils.copyWithAmount(current, left) : null, ItemUtils.copyWithAmount(current, take));
        }
        if (current == null) {
            if (effectiveLimit(slotLimit, cursor) <= 0) {
                return null;
            }
            return new SlotOutcome(ItemUtils.copyWithAmount(cursor, 1), remainderOf(cursor, 1));
        }
        if (ItemUtils.isSimilar(current, cursor)) {
            if (effectiveLimit(slotLimit, current) - current.getAmount() <= 0) {
                return null;
            }
            return new SlotOutcome(ItemUtils.copyWithAmount(current, current.getAmount() + 1), remainderOf(cursor, 1));
        }
        return computeSwap(current, cursor, slotLimit);
    }

    // 不相似交换: 原版要求光标堆不超过槽位上限, 否则无操作
    @Nullable
    private static SlotOutcome computeSwap(ItemStack current, ItemStack cursor, int slotLimit) {
        if (cursor.getAmount() > effectiveLimit(slotLimit, cursor)) {
            return null;
        }
        return new SlotOutcome(cursor.clone(), current);
    }

    // 数字键交换: 点击槽与当前 lower 的实际热键槽在同一个库存事务内整堆互换.
    private static void swapWithHotbar(Context context, LinkedSlot source, int windowSlot, int hotbarButton) {
        LinkedSlot target = context.hotbarLink(hotbarButton);
        if (target == null) {
            context.markDirty(windowSlot);
            return;
        }
        if (physicalKey(source).equals(physicalKey(target))) {
            context.markDirty(windowSlot);
            return;
        }

        swapLinks(reasonOf(context, ClickType.NUMBER_KEY), source, target);
        context.markDirty(windowSlot);
    }

    // 原版 SWAP 不检查光标; 副手不是 storage contents, 在库存事务提交后于同一 owner 线程应用.
    private static void swapWithOffhand(Context context, LinkedSlot source, int windowSlot) {
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
                reasonOf(context, ClickType.SWAP_OFFHAND),
                plan.scoper().apply(List.of(new SlotDelta(source.slot(), current, offhand))),
                false
        );
        if (result instanceof TransactionResult.Committed) {
            context.offhand(current);
        }
        context.markDirty(windowSlot);
    }

    private static void swapLinks(UpdateReason reason, LinkedSlot source, LinkedSlot target) {
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

    // 丢弃: 原版 THROW 要求光标为空; 先经事务扣槽再丢出
    private static void dropFromSlot(Context context, LinkedSlot link, int windowSlot, boolean fullStack) {
        if (!context.cursor().isEmpty()) {
            context.markDirty(windowSlot);
            return;
        }

        UpdateReason reason = reasonOf(context, fullStack ? ClickType.CONTROL_DROP : ClickType.DROP);
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

    // 创造模式中键: 复制整堆到空光标, 槽位不变; 纯读路径, 允许读到镜像的轻微滞后
    private static void creativeClone(Context context, LinkedSlot link, int windowSlot) {
        @Nullable ItemStack current = link.inventory().itemAt(link.slot());
        if (context.viewer().getGameMode() != GameMode.CREATIVE || !context.cursor().isEmpty() || current == null) {
            context.markDirty(windowSlot);
            return;
        }
        context.cursor(ItemUtils.copyWithAmount(current, current.getMaxStackSize()));
        context.markDirty(windowSlot);
    }

    // ---- 快速转移 ----

    // 按 ADD 优先级在全部连接库存间转移; source inventory 会从目标域排除
    private static void shiftFromLink(Context context, LinkedSlot link, int windowSlot, ClickType clickType) {
        // 快速空判可基于滞后镜像, 真正的读取在各目标的事务窗口内完成
        if (link.inventory().itemAt(link.slot()) == null) {
            context.markDirty(windowSlot);
            return;
        }
        UpdateReason reason = reasonOf(context, clickType);
        SparrowInventory.SlotKey sourceKey = physicalKey(link);

        for (Inventory target : addTargets(context, link.inventory())) {
            MoveOutcome outcome = moveIntoInventory(reason, link, target, sourceKey);
            if (outcome == MoveOutcome.MOVED || outcome == MoveOutcome.REJECTED) {
                // 有进展即停; 事务被取消或冲突同样终止本次转移
                context.markDirty(windowSlot);
                return;
            }
        }
        context.markDirty(windowSlot);
    }

    private enum MoveOutcome {
        MOVED, // 有进展, 转移终止
        FULL, // 目标无空间, 尝试下一个
        REJECTED // 事务被取消或冲突, 转移终止且零变更
    }

    // 源库存槽与目标库存合并为同一个事务: 跨根全成全败, 源槽读取在双方对账后的快照上
    private static MoveOutcome moveIntoInventory(UpdateReason reason, LinkedSlot source, Inventory target, SparrowInventory.SlotKey sourceKey) {
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

    // ---- 双击收集 ----

    // 收集域 = 全部参与库存按 COLLECT 优先级逐库规划并一次提交.
    // 跨域按最终物理槽去重;事务被否决时光标保持零变更.
    private static void collectToCursor(Context context) {
        ItemStack cursor = context.cursor();
        int space = cursor.getMaxStackSize() - cursor.getAmount();
        if (space <= 0) {
            return;
        }
        UpdateReason reason = reasonOf(context, ClickType.DOUBLE_CLICK);
        int collected = 0;
        HashSet<SparrowInventory.SlotKey> coveredSlots = new HashSet<>();
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

    // ---- 事务与玩家侧辅助 ----

    private static UpdateReason reasonOf(Context context, ClickType clickType) {
        return new UpdateReason.PlayerClick(context.viewer(), clickType);
    }

    // 库存槽的单槽写模板: 规划快照上读取, 计算, 经同一规划提交; 光标在提交成功后应用
    private static void applyLinkSemantics(Context context, UpdateReason reason, LinkedSlot link, int windowSlot, SlotComputation computation) {
        SparrowInventory inventory = (SparrowInventory) link.inventory();
        SparrowInventory.PlanContext plan = inventory.openPlanForWrite();
        if (!plan.writable(link.slot())) {
            context.markDirty(windowSlot);
            return;
        }
        @Nullable ItemStack current = plan.snapshot()[link.slot()];

        SlotOutcome outcome = computation.compute(current, inventory.slotMaxStackSize(link.slot()));
        if (outcome == null) {
            context.markDirty(windowSlot);
            return;
        }
        TransactionResult result = InventoryTransactions.commit(
                reason,
                plan.scoper().apply(List.of(new SlotDelta(link.slot(), current, outcome.slotAfter()))),
                false
        );
        if (result instanceof TransactionResult.Committed) {
            context.cursor(outcome.cursorAfter());
        }
        context.markDirty(windowSlot);
    }

    // 槽位有效上限 = min(槽自身上限, 物品自身上限)
    private static int effectiveLimit(int slotLimit, ItemStack item) {
        return Math.min(slotLimit, item.getMaxStackSize());
    }

    private static int effectiveCapacity(LinkedSlot link, ItemStack item) {
        return Math.min(link.inventory().slotMaxStackSize(link.slot()), item.getMaxStackSize());
    }

    @NotNull
    private static ItemStack remainderOf(ItemStack cursor, int taken) {
        int left = cursor.getAmount() - taken;
        return left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemStack.empty();
    }

    // 快速转移的目标库存: 排除源库存, 按 ADD 优先级降序且保持显示顺序稳定
    private static List<Inventory> addTargets(Context context, @Nullable Inventory exclude) {
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

    private static SparrowInventory.SlotKey physicalKey(LinkedSlot link) {
        return ((SparrowInventory) link.inventory()).physicalKey(link.slot());
    }

    private static void markAllDirty(Context context, List<Integer> windowSlots) {
        for (int i = 0; i < windowSlots.size(); i++) {
            context.markDirty(windowSlots.get(i));
        }
    }

    /**
     * 单槽语义的纯计算结果: 槽位新值与光标新值; {@code null} 表示无操作.
     */
    private record SlotOutcome(@Nullable ItemStack slotAfter, @NotNull ItemStack cursorAfter) {
    }

    private interface SlotComputation {

        @Nullable
        SlotOutcome compute(@Nullable ItemStack current, int slotLimit);
    }

    private record DragTarget(@NotNull LinkedSlot link, @Nullable ItemStack current, int capacity) {
    }
}
