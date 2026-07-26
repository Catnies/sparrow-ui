package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.CollectResult;
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
 * 光标与玩家背包是玩家实体线程私有状态, 在事务提交成功后无条件应用, 永不失败,
 * 因此不存在半点击. 事务被 pre 观察者取消或乐观校验冲突时, 本次点击零变更,
 * 涉及槽位被标脏以纠正客户端预测.
 * <p>库存槽的读取一律发生在 {@code openPlanForWrite} 之后的规划快照上 —— 读取
 * 与乐观校验基准同源, 镜像型库存的对账先于读取完成, 任何插入写(外部容器变更,
 * 异步写者)都会使提交降级为 Conflicted 而不是基于陈旧值的覆盖.
 * <p>代理菜单没有任何原版 Slot, 点击包也不会到达 NMS 逻辑, 因此纯玩家背包内的
 * 操作同样由本引擎执行 —— 它们只触碰玩家侧状态, 不产生库存事务与事件.
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
         * 返回窗口槽位连接的库存槽, 未连接库存(Item, 空槽或玩家区域)时返回 {@code null}.
         */
        @Nullable
        LinkedSlot linkAt(int windowSlot);

        /**
         * 返回窗口槽位的显示路径是否被冻结; 冻结槽不参与任何语义.
         */
        boolean frozenAt(int windowSlot);

        /**
         * 返回窗口槽位映射的玩家背包槽号, 非玩家区域返回 -1.
         */
        int lowerSlotAt(int windowSlot);

        /**
         * 布局是否包含真实玩家背包区域. 为 {@code false} 时(split, merged 布局)
         * 所有触碰隐藏真实背包的语义分支被禁用.
         */
        boolean hasPlayerInventory();

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
         * 玩家背包槽的物品快照.
         */
        @Nullable
        ItemStack lowerAt(int inventorySlot);

        /**
         * 权威覆盖玩家背包槽.
         */
        void lowerAt(int inventorySlot, @Nullable ItemStack item);

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
        int lowerSlot = context.lowerSlotAt(windowSlot);
        LinkedSlot link = lowerSlot >= 0 ? null : context.linkAt(windowSlot);

        if (link == null && lowerSlot < 0) {
            // Item 或空槽: 只有双击收集与槽位无关, 其余交回 Item 分派
            if (clickType == ClickType.DOUBLE_CLICK && !context.cursor().isEmpty()) {
                collectToCursor(context);
                return true;
            }
            return false;
        }
        if (link != null && context.frozenAt(windowSlot)) {
            context.markDirty(windowSlot);
            return true;
        }

        switch (clickType) {
            case LEFT -> pickupOrPlace(context, link, lowerSlot, windowSlot, ClickType.LEFT);
            case RIGHT -> pickupOrPlace(context, link, lowerSlot, windowSlot, ClickType.RIGHT);
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                if (link != null) {
                    shiftFromLink(context, link, windowSlot, clickType);
                } else {
                    shiftFromLower(context, lowerSlot, windowSlot, clickType);
                }
            }
            case NUMBER_KEY -> swapWithPlayerSlot(context, link, lowerSlot, windowSlot, hotbarButton, false);
            case SWAP_OFFHAND -> swapWithPlayerSlot(context, link, lowerSlot, windowSlot, -1, true);
            case DROP, CONTROL_DROP -> dropFromSlot(context, link, lowerSlot, windowSlot, clickType == ClickType.CONTROL_DROP);
            case DOUBLE_CLICK -> {
                // 原版语义: 光标非空且被点槽为空时才收集
                if (!context.cursor().isEmpty() && readSlot(context, link, lowerSlot) == null) {
                    collectToCursor(context);
                } else {
                    context.markDirty(windowSlot);
                }
            }
            case MIDDLE -> creativeClone(context, link, lowerSlot, windowSlot);
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
     * 处理一次已完成的拖拽分配: 全部参与库存槽进入同一个事务, 玩家背包槽在
     * 提交成功后应用, 余量回到光标. 显示同一物理槽的多个窗口槽只参与一次.
     */
    public static void handleDrag(@NotNull Context context, @NotNull ClickType clickType, @NotNull List<Integer> windowSlots) {
        ItemStack cursor = context.cursor();
        boolean creative = clickType == ClickType.MIDDLE;
        if (cursor.isEmpty() || (creative && context.viewer().getGameMode() != GameMode.CREATIVE)) {
            markAllDirty(context, windowSlots);
            return;
        }

        // 阶段一: 按窗口槽收集参与描述, 库存槽先按物理锚点去重(镜像显示只参与一次),
        // 再按库存分组取得写规划快照 —— 读取全部发生在对账后的快照上
        Map<Inventory, SparrowInventory.PlanContext> plans = new LinkedHashMap<>();
        HashSet<SparrowInventory.Anchor> seenAnchors = new HashSet<>();
        HashSet<Integer> seenLowerSlots = new HashSet<>();
        List<DragTarget> targets = new ArrayList<>(windowSlots.size());
        for (int i = 0; i < windowSlots.size(); i++) {
            int windowSlot = windowSlots.get(i);
            int lowerSlot = context.lowerSlotAt(windowSlot);
            LinkedSlot link = lowerSlot >= 0 ? null : context.linkAt(windowSlot);
            if (link == null && lowerSlot < 0) {
                continue;
            }
            if (link != null && context.frozenAt(windowSlot)) {
                continue;
            }

            @Nullable ItemStack current;
            if (link != null) {
                SparrowInventory inventory = (SparrowInventory) link.inventory();
                if (!seenAnchors.add(inventory.resolveSlot(link.slot()))) {
                    continue;
                }
                SparrowInventory.PlanContext plan = plans.computeIfAbsent(inventory, key -> inventory.openPlanForWrite());
                current = plan.snapshot()[link.slot()];
            } else {
                if (!seenLowerSlots.add(lowerSlot)) {
                    continue;
                }
                current = context.lowerAt(lowerSlot);
            }
            if (current != null && !ItemUtils.isSimilar(current, cursor)) {
                continue;
            }
            int capacity = effectiveCapacity(link, cursor) - ItemUtils.amountOf(current);
            if (capacity <= 0) {
                continue;
            }
            targets.add(new DragTarget(link, lowerSlot, current, capacity));
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

        // 阶段二: 逐槽计算实放量, 库存槽 delta 归入各自规划, 背包写入先累积后应用
        Map<Inventory, List<SlotDelta>> deltasByInventory = new LinkedHashMap<>();
        List<Runnable> lowerWrites = new ArrayList<>();
        int budget = creative ? Integer.MAX_VALUE : cursor.getAmount();
        int placedTotal = 0;
        for (int i = 0; i < targets.size() && budget > 0; i++) {
            DragTarget target = targets.get(i);
            int placed = Math.min(Math.min(perSlot, target.capacity()), budget);
            if (placed <= 0) {
                continue;
            }
            ItemStack after = ItemUtils.copyWithAmount(cursor, ItemUtils.amountOf(target.current()) + placed);
            if (target.link() != null) {
                deltasByInventory.computeIfAbsent(target.link().inventory(), inventory -> new ArrayList<>())
                        .add(new SlotDelta(target.link().slot(), target.current(), after));
            } else {
                int lowerSlot = target.lowerSlot();
                lowerWrites.add(() -> context.lowerAt(lowerSlot, after));
            }
            if (!creative) {
                budget -= placed;
                placedTotal += placed;
            }
        }

        // 库存侧作为一个事务提交; 取消或冲突时整个拖拽零变更
        if (!deltasByInventory.isEmpty()) {
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
        }
        for (int i = 0; i < lowerWrites.size(); i++) {
            lowerWrites.get(i).run();
        }
        if (!creative && placedTotal > 0) {
            int left = cursor.getAmount() - placedTotal;
            context.cursor(left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemStack.empty());
        }
        markAllDirty(context, windowSlots);
    }

    // ---- 单槽: 拾起, 放入, 合并与交换 ----

    // 左右键的取放语义: 库存槽经规划快照读取并提交事务, 背包槽直接执行
    private static void pickupOrPlace(Context context, @Nullable LinkedSlot link, int lowerSlot, int windowSlot, ClickType clickType) {
        ItemStack cursor = context.cursor();
        if (link != null) {
            applyLinkSemantics(context, reasonOf(context, clickType), link, windowSlot, (current, slotLimit) ->
                    clickType == ClickType.LEFT
                            ? computeLeftClick(current, cursor, slotLimit)
                            : computeRightClick(current, cursor, slotLimit));
            return;
        }

        @Nullable ItemStack current = context.lowerAt(lowerSlot);
        SlotOutcome outcome = clickType == ClickType.LEFT
                ? computeLeftClick(current, cursor, Integer.MAX_VALUE)
                : computeRightClick(current, cursor, Integer.MAX_VALUE);
        if (outcome == null) {
            context.markDirty(windowSlot);
            return;
        }
        context.lowerAt(lowerSlot, outcome.slotAfter());
        context.cursor(outcome.cursorAfter());
        context.markDirty(windowSlot);
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

    // 数字键与副手交换: 点击槽与玩家背包槽(或副手)整堆互换; 原版 SWAP 不检查光标.
    // 超出槽位上限的原版拆分细节以整堆交换近似, 权威写不受上限约束
    private static void swapWithPlayerSlot(Context context, @Nullable LinkedSlot link, int lowerSlot, int windowSlot, int hotbarButton, boolean offhand) {
        // 隐藏真实背包的布局下禁用: 不能与玩家看不见的槽位交换
        if (!context.hasPlayerInventory()) {
            context.markDirty(windowSlot);
            return;
        }
        if (lowerSlot >= 0 && !offhand && lowerSlot == hotbarButton) {
            context.markDirty(windowSlot);
            return;
        }
        @Nullable ItemStack other = offhand ? context.offhand() : context.lowerAt(hotbarButton);

        if (link != null) {
            UpdateReason reason = reasonOf(context, offhand ? ClickType.SWAP_OFFHAND : ClickType.NUMBER_KEY);
            SparrowInventory inventory = (SparrowInventory) link.inventory();
            SparrowInventory.PlanContext plan = inventory.openPlanForWrite();
            @Nullable ItemStack current = plan.snapshot()[link.slot()];
            if (current == null && other == null) {
                context.markDirty(windowSlot);
                return;
            }
            TransactionResult result = InventoryTransactions.commit(
                    reason,
                    plan.scoper().apply(List.of(new SlotDelta(link.slot(), current, other))),
                    false
            );
            if (result instanceof TransactionResult.Committed) {
                writePlayerSide(context, hotbarButton, offhand, current);
            }
            context.markDirty(windowSlot);
            return;
        }

        @Nullable ItemStack current = context.lowerAt(lowerSlot);
        if (current == null && other == null) {
            context.markDirty(windowSlot);
            return;
        }
        context.lowerAt(lowerSlot, other);
        writePlayerSide(context, hotbarButton, offhand, current);
        context.markDirty(windowSlot);
    }

    private static void writePlayerSide(Context context, int hotbarButton, boolean offhand, @Nullable ItemStack item) {
        if (offhand) {
            context.offhand(item);
        } else {
            context.lowerAt(hotbarButton, item);
        }
    }

    // 丢弃: 原版 THROW 要求光标为空; 先扣槽后丢出
    private static void dropFromSlot(Context context, @Nullable LinkedSlot link, int lowerSlot, int windowSlot, boolean fullStack) {
        if (!context.cursor().isEmpty()) {
            context.markDirty(windowSlot);
            return;
        }

        if (link != null) {
            UpdateReason reason = reasonOf(context, fullStack ? ClickType.CONTROL_DROP : ClickType.DROP);
            SparrowInventory inventory = (SparrowInventory) link.inventory();
            SparrowInventory.PlanContext plan = inventory.openPlanForWrite();
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
            return;
        }

        @Nullable ItemStack current = context.lowerAt(lowerSlot);
        if (current == null) {
            context.markDirty(windowSlot);
            return;
        }
        int take = fullStack ? current.getAmount() : 1;
        int left = current.getAmount() - take;
        context.lowerAt(lowerSlot, left > 0 ? ItemUtils.copyWithAmount(current, left) : null);
        context.drop(ItemUtils.copyWithAmount(current, take));
        context.markDirty(windowSlot);
    }

    // 创造模式中键: 复制整堆到空光标, 槽位不变; 纯读路径, 允许读到镜像的轻微滞后
    private static void creativeClone(Context context, @Nullable LinkedSlot link, int lowerSlot, int windowSlot) {
        @Nullable ItemStack current = readSlot(context, link, lowerSlot);
        if (context.viewer().getGameMode() != GameMode.CREATIVE || !context.cursor().isEmpty() || current == null) {
            context.markDirty(windowSlot);
            return;
        }
        context.cursor(ItemUtils.copyWithAmount(current, current.getMaxStackSize()));
        context.markDirty(windowSlot);
    }

    // ---- 快速转移 ----

    // 库存槽 -> 其他连接库存(按 ADD 优先级降序, 有进展即停) -> 玩家背包
    private static void shiftFromLink(Context context, LinkedSlot link, int windowSlot, ClickType clickType) {
        // 快速空判可基于滞后镜像, 真正的读取在各目标的事务窗口内完成
        if (link.inventory().itemAt(link.slot()) == null) {
            context.markDirty(windowSlot);
            return;
        }
        UpdateReason reason = reasonOf(context, clickType);

        for (Inventory target : addTargets(context, link.inventory())) {
            MoveOutcome outcome = moveIntoInventory(reason, link, target);
            if (outcome == MoveOutcome.MOVED || outcome == MoveOutcome.REJECTED) {
                // 有进展即停; 事务被取消或冲突同样终止本次转移
                context.markDirty(windowSlot);
                return;
            }
        }

        if (context.hasPlayerInventory()) {
            moveIntoLower(context, link, reason);
        }
        context.markDirty(windowSlot);
    }

    // 库存槽 -> 玩家背包: 库存扣减经事务, 背包写在提交成功后应用
    private static void moveIntoLower(Context context, LinkedSlot link, UpdateReason reason) {
        SparrowInventory inventory = (SparrowInventory) link.inventory();
        SparrowInventory.PlanContext plan = inventory.openPlanForWrite();
        @Nullable ItemStack current = plan.snapshot()[link.slot()];
        if (current == null) {
            return;
        }
        LowerPlacement placement = planLowerPlacement(context, current);
        if (placement.moved() == 0) {
            return;
        }
        int left = current.getAmount() - placement.moved();
        TransactionResult result = InventoryTransactions.commit(
                reason,
                plan.scoper().apply(List.of(new SlotDelta(link.slot(), current, left > 0 ? ItemUtils.copyWithAmount(current, left) : null))),
                false
        );
        if (result instanceof TransactionResult.Committed) {
            placement.apply();
        }
    }

    // 玩家背包槽 -> 连接库存(按 ADD 优先级降序, 有进展即停; 事务被否决即终止)
    private static void shiftFromLower(Context context, int lowerSlot, int windowSlot, ClickType clickType) {
        @Nullable ItemStack current = context.lowerAt(lowerSlot);
        if (current == null) {
            context.markDirty(windowSlot);
            return;
        }
        UpdateReason reason = reasonOf(context, clickType);

        for (Inventory target : addTargets(context, null)) {
            AddResult added = target.add(reason, current);
            if (!(added.result() instanceof TransactionResult.Committed)) {
                break;
            }
            int moved = current.getAmount() - added.remaining();
            if (moved > 0) {
                int left = current.getAmount() - moved;
                context.lowerAt(lowerSlot, left > 0 ? ItemUtils.copyWithAmount(current, left) : null);
                break;
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
    private static MoveOutcome moveIntoInventory(UpdateReason reason, LinkedSlot source, Inventory target) {
        SparrowInventory sourceInventory = (SparrowInventory) source.inventory();
        SparrowInventory targetInventory = (SparrowInventory) target;

        SparrowInventory.PlanContext sourcePlan = sourceInventory.openPlanForWrite();
        SparrowInventory.PlanContext targetPlan = targetInventory.openPlanForWrite();
        @Nullable ItemStack current = sourcePlan.snapshot()[source.slot()];
        if (current == null) {
            return MoveOutcome.FULL;
        }
        InventoryPlanner.AddPlan addPlan = InventoryPlanner.planAdd(
                targetPlan.snapshot(),
                current,
                targetInventory.iterationOrder(OperationCategory.ADD),
                targetInventory::slotMaxStackSize
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
        return InventoryTransactions.commit(reason, scopes, false) instanceof TransactionResult.Committed
                ? MoveOutcome.MOVED
                : MoveOutcome.REJECTED;
    }

    // ---- 双击收集 ----

    // 收集域 = 全部参与库存(按 COLLECT 优先级降序, 每库存一个事务) + 玩家背包扫尾.
    // 事务被否决即终止后续收集与扫尾; 已提交的收集量必须进入光标以维持守恒.
    private static void collectToCursor(Context context) {
        ItemStack cursor = context.cursor();
        int space = cursor.getMaxStackSize() - cursor.getAmount();
        if (space <= 0) {
            return;
        }
        UpdateReason reason = reasonOf(context, ClickType.DOUBLE_CLICK);
        int collected = 0;
        boolean rejected = false;

        List<Inventory> domain = new ArrayList<>(context.linkedInventories());
        domain.sort((left, right) -> Integer.compare(
                right.guiPriority(OperationCategory.COLLECT),
                left.guiPriority(OperationCategory.COLLECT)
        ));
        for (int i = 0; i < domain.size() && collected < space; i++) {
            CollectResult result = domain.get(i).collect(reason, cursor, space - collected);
            if (!(result.result() instanceof TransactionResult.Committed)) {
                rejected = true;
                break;
            }
            collected += result.collected();
        }

        // 玩家背包扫尾: 与背包放置同序(主背包在前), 玩家侧直接写
        if (!rejected && context.hasPlayerInventory()) {
            int[] order = lowerPlacementOrder();
            for (int i = 0; i < order.length && collected < space; i++) {
                int inventorySlot = order[i];
                @Nullable ItemStack stack = context.lowerAt(inventorySlot);
                if (stack == null || !ItemUtils.isSimilar(stack, cursor)) {
                    continue;
                }
                int take = Math.min(stack.getAmount(), space - collected);
                int left = stack.getAmount() - take;
                context.lowerAt(inventorySlot, left > 0 ? ItemUtils.copyWithAmount(stack, left) : null);
                collected += take;
            }
        }

        if (collected > 0) {
            context.cursor(ItemUtils.copyWithAmount(cursor, cursor.getAmount() + collected));
        }
    }

    // ---- 事务与玩家侧辅助 ----

    private static UpdateReason reasonOf(Context context, ClickType clickType) {
        return new UpdateReason.PlayerClick(context.viewer(), clickType);
    }

    @Nullable
    private static ItemStack readSlot(Context context, @Nullable LinkedSlot link, int lowerSlot) {
        return link != null ? link.inventory().itemAt(link.slot()) : context.lowerAt(lowerSlot);
    }

    // 库存槽的单槽写模板: 规划快照上读取, 计算, 经同一规划提交; 光标在提交成功后应用
    private static void applyLinkSemantics(Context context, UpdateReason reason, LinkedSlot link, int windowSlot, SlotComputation computation) {
        SparrowInventory inventory = (SparrowInventory) link.inventory();
        SparrowInventory.PlanContext plan = inventory.openPlanForWrite();
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

    // 槽位有效上限 = min(槽自身上限, 物品自身上限); 玩家背包槽的槽上限视为无限
    private static int effectiveLimit(int slotLimit, ItemStack item) {
        return Math.min(slotLimit, item.getMaxStackSize());
    }

    private static int effectiveCapacity(@Nullable LinkedSlot link, ItemStack item) {
        if (link == null) {
            return item.getMaxStackSize();
        }
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

    // 规划玩家背包的放置: 先合并主背包与热键栏的相似堆, 再占用空槽(主背包 9-35 优先)
    private static LowerPlacement planLowerPlacement(Context context, ItemStack item) {
        List<Runnable> writes = new ArrayList<>();
        int remaining = item.getAmount();

        int[] order = lowerPlacementOrder();
        // 第一遍: 合并相似且未满的堆
        for (int i = 0; i < order.length && remaining > 0; i++) {
            int inventorySlot = order[i];
            @Nullable ItemStack current = context.lowerAt(inventorySlot);
            if (current == null || !ItemUtils.isSimilar(current, item)) {
                continue;
            }
            int space = item.getMaxStackSize() - current.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            ItemStack after = ItemUtils.copyWithAmount(current, current.getAmount() + moved);
            int slot = inventorySlot;
            writes.add(() -> context.lowerAt(slot, after));
            remaining -= moved;
        }
        // 第二遍: 占用空槽
        for (int i = 0; i < order.length && remaining > 0; i++) {
            int inventorySlot = order[i];
            if (context.lowerAt(inventorySlot) != null) {
                continue;
            }
            int moved = Math.min(item.getMaxStackSize(), remaining);
            ItemStack after = ItemUtils.copyWithAmount(item, moved);
            int slot = inventorySlot;
            writes.add(() -> context.lowerAt(slot, after));
            remaining -= moved;
        }
        return new LowerPlacement(item.getAmount() - remaining, writes);
    }

    // 玩家背包的放置顺序: 主背包 9-35 在前, 热键栏 0-8 在后
    private static int[] lowerPlacementOrder() {
        int[] order = new int[36];
        for (int i = 0; i < 27; i++) {
            order[i] = i + 9;
        }
        for (int i = 0; i < 9; i++) {
            order[27 + i] = i;
        }
        return order;
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

    private record DragTarget(@Nullable LinkedSlot link, int lowerSlot, @Nullable ItemStack current, int capacity) {
    }

    // 玩家背包放置的延迟应用: 全部写在库存事务提交成功后执行
    private record LowerPlacement(int moved, List<Runnable> writes) {

        private void apply() {
            for (int i = 0; i < this.writes.size(); i++) {
                this.writes.get(i).run();
            }
        }
    }
}
