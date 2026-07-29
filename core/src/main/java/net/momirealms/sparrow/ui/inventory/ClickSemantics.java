package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Window 里的一次点击"翻译"成Inventory事务与玩家侧变化的引擎.
 * <p>每种点击都是一笔事务: Inventory侧的变化(可能横跨多个根Inventory)走事务管线.
 * <p>读Inventory槽永远发生在 {@code openPlanForWrite} 之后的规划快照上:
 * ReferencingInventory 在这一刻先和外部容器进行比对, 任何并发写入
 * 都会让提交变成 Conflicted, 而不会拿旧值覆盖新值.
 * <p>代理菜单没有任何原版 Slot, 点击包也不会到达 NMS 逻辑;
 * 默认的下方玩家背包通过 ReferencingInventory 接入本引擎, 所以纯背包操作同样走Inventory事务.
 * <p>Window 通过 {@link Context} 提供槽位路由与玩家侧读写, 本类不持有任何状态,
 * 所有方法都要在玩家实体线程上调用.
 */
public final class ClickSemantics {

    /**
     * Window 层提供的交互上下文: 存储点击语义需要的 "槽位路由" 和 "玩家侧读写" .
     * <p>读取方法返回的是可以随便持有的快照; 写入方法是权威覆盖, 由实现方负责把变化
     * 同步给客户端(标脏, 光标脏位等).
     */
    public interface Context {

        /**
         * 本次操作对应的玩家.
         *
         * @return 交互的玩家
         */
        @NotNull
        Player viewer();

        /**
         * 查出某个窗口槽背后连着哪个Inventory的哪个槽;
         * Item 与空槽背后没有Inventory, 返回 {@code null}.
         *
         * @param windowSlot 窗口槽号
         * @return 连接的Inventory槽, 没有连接时为 {@code null}
         */
        @Nullable
        LinkedSlot linkAt(int windowSlot);

        /**
         * 判断某个窗口槽的显示路径是否被冻结;
         * 冻结槽不参与任何点击语义.
         *
         * @param windowSlot 窗口槽号
         * @return 冻结返回 {@code true}
         */
        boolean frozenAt(int windowSlot);

        /**
         * 查出数字键要交换的目标: 当前 lower 快捷栏某个按键位置实际连着的Inventory槽;
         * 该位置不是Inventory元素或路径被冻结时返回 {@code null}.
         *
         * @param hotbarButton 热键编号, 0 到 8
         * @return 连接的Inventory槽, 不可交互时为 {@code null}
         */
        @Nullable
        LinkedSlot hotbarLink(int hotbarButton);

        /**
         * 按显示顺序列出参与本次点击语义的全部Inventory(去重), 快速转移与双击收集在它们里面找目标;
         * 只通过冻结槽位或协议外槽位连进来的Inventory不应包含在内.
         *
         * @return 参与语义的全部Inventory
         */
        @NotNull
        List<Inventory> linkedInventories();

        /**
         * 光标上正拿着的物品的快照;
         * 没拿东西时返回空物品而不是 {@code null}.
         *
         * @return 光标物品快照
         */
        @NotNull
        ItemStack cursor();

        /**
         * 权威覆盖光标上的物品.
         *
         * @param cursor 新的光标物品
         */
        void cursor(@NotNull ItemStack cursor);

        /**
         * 副手物品的快照.
         *
         * @return 副手物品快照, 空副手为 {@code null}
         */
        @Nullable
        ItemStack offhand();

        /**
         * 权威覆盖副手物品.
         *
         * @param item 新的副手物品, {@code null} 表示清空
         */
        void offhand(@Nullable ItemStack item);

        /**
         * 以玩家名义把物品丢进世界, 走正常的掉落逻辑.
         *
         * @param item 要丢出的物品
         */
        void drop(@NotNull ItemStack item);

        /**
         * 标记某个窗口槽需要重新核对: 下一次同步时客户端会被拉回服务端的真实状态,
         * 用来纠正客户端的点击预测.
         *
         * @param windowSlot 窗口槽号
         */
        void markDirty(int windowSlot);
    }

    /**
     * 一个窗口槽背后连接的 Inventory 槽位.
     *
     * @param inventory 目标 Inventory
     * @param slot Inventory 里的槽号
     */
    public record LinkedSlot(@NotNull Inventory inventory, int slot) {
    }

    private ClickSemantics() {
    }

    /**
     * 根据当前只读状态, 预估这次点击在 Paper 里会上报成哪个 {@link InventoryAction}.
     * 其他已支持但点了没效果的操作返回 {@link InventoryAction#NOTHING}.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 已解析的点击类型
     * @param hotbarButton NUMBER_KEY 的热键编号, 其他点击传 {@code -1}
     * @param windowSlot 窗口原始槽号, 或 {@link InventoryView#OUTSIDE}
     * @return 按当前只读状态预估的 Bukkit 操作
     */
    @NotNull
    public static InventoryAction estimateInventoryAction(
            @NotNull Context context,
            @NotNull ClickType clickType,
            int hotbarButton,
            int windowSlot
    ) {
        ItemStack cursor = context.cursor();
        if (windowSlot == InventoryView.OUTSIDE) {
            return estimateOutsideInventoryAction(cursor, clickType);
        }
        if (clickType == ClickType.UNKNOWN || clickType == ClickType.CREATIVE) {
            return InventoryAction.UNKNOWN;
        }

        LinkedSlot link = context.linkAt(windowSlot);
        if (link == null) {
            return clickType == ClickType.DOUBLE_CLICK
                    ? estimateCollectToCursor(context, cursor)
                    : InventoryAction.NOTHING;
        }
        if (context.frozenAt(windowSlot)) {
            return InventoryAction.NOTHING;
        }

        @Nullable ItemStack current = link.inventory().itemAt(link.slot());
        return switch (clickType) {
            case LEFT -> estimateLeftClick(link, cursor, current);
            case RIGHT -> estimateRightClick(link, cursor, current);
            case SHIFT_LEFT, SHIFT_RIGHT -> current == null ? InventoryAction.NOTHING : InventoryAction.MOVE_TO_OTHER_INVENTORY;
            case NUMBER_KEY -> estimateHotbarSwap(context, link, current, hotbarButton);
            case SWAP_OFFHAND -> current == null && ItemUtils.isNullOrEmpty(context.offhand())
                    ? InventoryAction.NOTHING
                    : InventoryAction.HOTBAR_SWAP;
            case DROP -> cursor.isEmpty() && current != null ? InventoryAction.DROP_ONE_SLOT : InventoryAction.NOTHING;
            case CONTROL_DROP -> cursor.isEmpty() && current != null ? InventoryAction.DROP_ALL_SLOT : InventoryAction.NOTHING;
            case DOUBLE_CLICK -> current == null ? estimateCollectToCursor(context, cursor) : InventoryAction.NOTHING;
            case MIDDLE -> context.viewer().getGameMode() == GameMode.CREATIVE && cursor.isEmpty() && current != null
                    ? InventoryAction.CLONE_STACK
                    : InventoryAction.NOTHING;
            case WINDOW_BORDER_LEFT, WINDOW_BORDER_RIGHT -> InventoryAction.NOTHING;
            case UNKNOWN, CREATIVE -> InventoryAction.UNKNOWN;
        };
    }

    /**
     * 预估点到窗口外的操作: 光标上有东西时左键丢整堆, 右键丢一个;
     *
     * @param cursor 当前光标物品
     * @param clickType 点击类型
     * @return 预估的 Bukkit 操作
     */
    @NotNull
    private static InventoryAction estimateOutsideInventoryAction(ItemStack cursor, ClickType clickType) {
        if (clickType == ClickType.UNKNOWN || clickType == ClickType.CREATIVE) {
            return InventoryAction.UNKNOWN;
        }
        if (cursor.isEmpty()) {
            return InventoryAction.NOTHING;
        }
        return switch (clickType) {
            case LEFT, WINDOW_BORDER_LEFT -> InventoryAction.DROP_ALL_CURSOR;
            case RIGHT, WINDOW_BORDER_RIGHT -> InventoryAction.DROP_ONE_CURSOR;
            default -> InventoryAction.NOTHING;
        };
    }

    /**
     * 预估左键点在某个Inventory槽上的操作: 拿起, 放入, 合并, 交换.
     *
     * @param link 被点的Inventory槽
     * @param cursor 当前光标物品
     * @param current 槽内现有物品, 空槽为 {@code null}
     * @return 预估的 Bukkit 操作
     */
    @NotNull
    private static InventoryAction estimateLeftClick(LinkedSlot link, ItemStack cursor, @Nullable ItemStack current) {
        SlotOutcome outcome = computeLeftClick(current, cursor, link.inventory().slotMaxStackSize(link.slot()));
        if (outcome == null) {
            return InventoryAction.NOTHING;
        }
        if (cursor.isEmpty()) {
            return InventoryAction.PICKUP_ALL;
        }
        if (current == null) {
            return InventoryAction.PLACE_ALL;
        }
        if (ItemUtils.isSimilar(current, cursor)) {
            int placed = cursor.getAmount() - outcome.cursorAfter().getAmount();
            if (placed == 1) {
                return InventoryAction.PLACE_ONE;
            }
            return outcome.cursorAfter().isEmpty() ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_SOME;
        }
        return InventoryAction.SWAP_WITH_CURSOR;
    }

    /**
     * 预估右键点在某个Inventory槽上的操作: 拿起一半, 放入一个, 交换.
     *
     * @param link 被点的Inventory槽
     * @param cursor 当前光标物品
     * @param current 槽内现有物品, 空槽为 {@code null}
     * @return 预估的 Bukkit 操作
     */
    @NotNull
    private static InventoryAction estimateRightClick(LinkedSlot link, ItemStack cursor, @Nullable ItemStack current) {
        SlotOutcome outcome = computeRightClick(current, cursor, link.inventory().slotMaxStackSize(link.slot()));
        if (outcome == null) {
            return InventoryAction.NOTHING;
        }
        if (cursor.isEmpty()) {
            return InventoryAction.PICKUP_HALF;
        }
        return ItemUtils.isSimilar(current, cursor) || current == null
                ? InventoryAction.PLACE_ONE
                : InventoryAction.SWAP_WITH_CURSOR;
    }

    /**
     * 预估数字键交换: 热键编号越界, 目标位置连不上Inventory, 源与目标是同一个物理槽,
     * 或者两边都空着, 都不会有操作.
     *
     * @param context 当前 Window 交互上下文
     * @param source 被点的Inventory槽
     * @param sourceItem 被点槽内的物品, 空槽为 {@code null}
     * @param hotbarButton 热键编号, 0 到 8
     * @return 预估的 Bukkit 操作
     */
    @NotNull
    private static InventoryAction estimateHotbarSwap(Context context, LinkedSlot source, @Nullable ItemStack sourceItem, int hotbarButton) {
        if (hotbarButton < 0 || hotbarButton > 8) {
            return InventoryAction.UNKNOWN;
        }
        LinkedSlot target = context.hotbarLink(hotbarButton);
        if (target == null || physicalKey(source).equals(physicalKey(target))) {
            return InventoryAction.NOTHING;
        }
        return sourceItem == null && target.inventory().itemAt(target.slot()) == null
                ? InventoryAction.NOTHING
                : InventoryAction.HOTBAR_SWAP;
    }

    /**
     * 预估双击收集: 光标没堆满, 且任一参与Inventory里还找得到相似物品, 就会发生收集.
     *
     * @param context 当前 Window 交互上下文
     * @param cursor 当前光标物品
     * @return 预估的 Bukkit 操作
     */
    @NotNull
    private static InventoryAction estimateCollectToCursor(Context context, ItemStack cursor) {
        if (cursor.isEmpty() || cursor.getAmount() >= cursor.getMaxStackSize()) {
            return InventoryAction.NOTHING;
        }
        List<Inventory> inventories = context.linkedInventories();
        for (int inventoryIndex = 0; inventoryIndex < inventories.size(); inventoryIndex++) {
            ItemStack[] snapshot = inventories.get(inventoryIndex).snapshot();
            for (int slot = 0; slot < snapshot.length; slot++) {
                if (ItemUtils.isSimilar(cursor, snapshot[slot])) {
                    return InventoryAction.COLLECT_TO_CURSOR;
                }
            }
        }
        return InventoryAction.NOTHING;
    }

    /**
     * 处理一次已经解析好的单击.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 已解析的点击类型
     * @param hotbarButton NUMBER_KEY 的热键编号, 其他点击传 {@code -1}
     * @param windowSlot 窗口原始槽号
     * @return 语义已接管返回 {@code true}; {@code false} 表示点的是装饰性 Item 或空槽,
     * 与Inventory无关, 交给调用方按原有的 Item 分派处理
     */
    public static boolean handleClick(@NotNull Context context, @NotNull ClickType clickType, int hotbarButton, int windowSlot) {
        LinkedSlot link = context.linkAt(windowSlot);

        if (link == null) {
            // Item 或空槽: 只有双击收集与槽位无关, 其余交回 Item 分派.
            // 原版双击收集只看光标上有没有东西, 跟点在哪个槽无关.
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
     * 处理点到窗口外的点击: 光标非空时按原版习惯丢出(左键整堆, 右键一个).
     * <p>先扣减光标再丢出, 与 Paper 的顺序一致.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 点击类型(WINDOW_BORDER_LEFT 丢整堆, 其余丢一个)
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
     * 处理一次已经完成的拖拽分配: 所有碰到的Inventory槽进同一笔事务, 分不完的部分留在光标上.
     * 多个窗口槽显示同一个物理槽时只分一次.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 拖拽按键(LEFT 均分, RIGHT 每槽一个, MIDDLE 创造模式每槽整堆且不消耗光标)
     * @param windowSlots 拖拽经过的全部窗口槽
     */
    public static void handleDrag(@NotNull Context context, @NotNull ClickType clickType, @NotNull List<Integer> windowSlots) {
        ItemStack cursor = context.cursor();
        boolean creative = clickType == ClickType.MIDDLE;
        if (cursor.isEmpty() || (creative && context.viewer().getGameMode() != GameMode.CREATIVE)) {
            markAllDirty(context, windowSlots);
            return;
        }

        // 跨 InventoryLink 按最终物理槽去重
        LinkedHashMap<SlotKey, LinkedSlot> candidates = new LinkedHashMap<>();
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

        // 按Inventory分组取得写规划快照, 读取全部发生在对账后的快照上
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

    /**
     * 左右键的取放入口: 从规划快照读出槽内物品, 算出点击后的槽与光标,
     * 提交事务成功后才把新光标写回玩家.
     *
     * @param context 当前 Window 交互上下文
     * @param link 被点的Inventory槽
     * @param windowSlot 被点的窗口槽, 仅用于标脏
     * @param clickType 左键还是右键
     */
    private static void pickupOrPlace(Context context, LinkedSlot link, int windowSlot, ClickType clickType) {
        ItemStack cursor = context.cursor();
        applyLinkSemantics(context, reasonOf(context, clickType), link, windowSlot, (current, slotLimit) ->
                clickType == ClickType.LEFT
                        ? computeLeftClick(current, cursor, slotLimit)
                        : computeRightClick(current, cursor, slotLimit));
    }

    /**
     * 算出左键点击后槽位与光标各自的新内容.
     * <p>四种情形: 光标空手就把整堆拿起来; 槽空就把光标物品尽量放进去; 两边相似就合并;
     * 两边不一样就整堆交换 (交换受槽位上限约束, 放不下就是无操作).
     *
     * @param current 槽内现有物品, 空槽为 {@code null}
     * @param cursor 当前光标物品
     * @param slotLimit 该槽的堆叠上限
     * @return 槽位与光标的新值; {@code null} 表示这次点击什么都不会发生
     */
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

    /**
     * 算出右键点击后槽位与光标各自的新内容.
     * <p>空光标就拿走一半 (向上取整); 槽空或两边相似就从光标放一个进去;
     * 两边不一样就交换.
     *
     * @param current 槽内现有物品, 空槽为 {@code null}
     * @param cursor 当前光标物品
     * @param slotLimit 该槽的堆叠上限
     * @return 槽位与光标的新值; {@code null} 表示这次点击什么都不会发生
     */
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

    /**
     * 算出"两边物品不一样"时的整堆交换: 槽位得到光标整堆, 光标得到槽内整堆.
     * 原版要求光标这堆不能超过槽位有效上限, 超了这次点击无效.
     *
     * @param current 槽内现有物品
     * @param cursor 当前光标物品
     * @param slotLimit 该槽的堆叠上限
     * @return 交换结果; 光标堆超上限时为 {@code null}
     */
    @Nullable
    private static SlotOutcome computeSwap(ItemStack current, ItemStack cursor, int slotLimit) {
        if (cursor.getAmount() > effectiveLimit(slotLimit, cursor)) {
            return null;
        }
        return new SlotOutcome(cursor.clone(), current);
    }

    /**
     * 数字键交换: 被点槽与当前 lower 对应热键位置背后的Inventory槽, 在同一笔事务里整堆互换.
     * 目标位置连不到Inventory, 或两边是同一个物理槽时, 什么也不做.
     *
     * @param context 当前 Window 交互上下文
     * @param source 被点的Inventory槽
     * @param windowSlot 被点的窗口槽, 仅用于标脏
     * @param hotbarButton 热键编号, 0 到 8
     */
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

    /**
     * 副手交换(F 键): 被点槽与副手整堆互换.
     * <p>原版这个操作不要求光标为空. 副手不属于Inventory数据, 是玩家线程私有状态,
     * 在Inventory事务提交成功后于同一线程直接套用; 两边都空时什么也不做.
     *
     * @param context 当前 Window 交互上下文
     * @param source 被点的Inventory槽
     * @param windowSlot 被点的窗口槽, 仅用于标脏
     */
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

    /**
     * 把两个Inventory槽在一笔事务里整堆互换; 同Inventory与跨Inventory两种情况分别展开,
     * 任一侧当前不可写, 或两边都为空时直接放弃.
     *
     * @param reason 变更原因
     * @param source 发起侧的Inventory槽
     * @param target 目标侧的Inventory槽
     */
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

    /**
     * 从槽里丢物品(Q 丢一个, Ctrl+Q 丢整堆): 原版要求光标必须空着;
     * 先经事务把槽内物品扣掉, 提交成功才把扣下的部分丢进世界.
     *
     * @param context 当前 Window 交互上下文
     * @param link 被点的Inventory槽
     * @param windowSlot 被点的窗口槽, 仅用于标脏
     * @param fullStack 是否丢整堆
     */
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

    /**
     * 创造模式中键: 把槽内物品按整堆复制到光标上, 槽位本身不动.
     * <p>这是纯读路径, 允许读到镜像的轻微滞后; 非创造模式, 光标非空, 或槽位为空时无效.
     *
     * @param context 当前 Window 交互上下文
     * @param link 被点的Inventory槽
     * @param windowSlot 被点的窗口槽, 仅用于标脏
     */
    private static void creativeClone(Context context, LinkedSlot link, int windowSlot) {
        @Nullable ItemStack current = link.inventory().itemAt(link.slot());
        if (context.viewer().getGameMode() != GameMode.CREATIVE || !context.cursor().isEmpty() || current == null) {
            context.markDirty(windowSlot);
            return;
        }
        context.cursor(ItemUtils.copyWithAmount(current, current.getMaxStackSize()));
        context.markDirty(windowSlot);
    }

    /**
     * Shift 快速转移: 按 ADD 优先级挨个尝试目标Inventory, 源槽扣减与目标Inventory的放入合在
     * 一笔事务里; 有进展就停, 事务被否决也停, 只有目标放满了才试下一个.
     *
     * @param context 当前 Window 交互上下文
     * @param link 被点的Inventory槽
     * @param windowSlot 被点的窗口槽, 仅用于标脏
     * @param clickType SHIFT_LEFT 还是 SHIFT_RIGHT
     */
    private static void shiftFromLink(Context context, LinkedSlot link, int windowSlot, ClickType clickType) {
        // 快速空判可基于滞后镜像, 真正的读取在各目标的事务窗口内完成
        if (link.inventory().itemAt(link.slot()) == null) {
            context.markDirty(windowSlot);
            return;
        }
        UpdateReason reason = reasonOf(context, clickType);
        SlotKey sourceKey = physicalKey(link);

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

    /**
     * 单次"往一个目标Inventory转移"的结果, 告诉外层循环该停还是该继续.
     */
    private enum MoveOutcome {
        MOVED,   // 至少移动了一个, 本次转移完成
        FULL,    // 目标一个都放不下, 换下一个目标试试
        REJECTED // 事务被取消或冲突, 本次转移终止且零变更
    }

    /**
     * 尝试把源槽的物品堆转移进一个目标Inventory: 源槽扣减与目标Inventory的合并放进同一笔事务.
     *
     * @param reason 变更原因
     * @param source 源Inventory槽
     * @param target 目标Inventory
     * @param sourceKey 源槽的物理身份, 用来防止把物品"转移到自己身上"
     * @return 本次转移的结果
     */
    private static MoveOutcome moveIntoInventory(UpdateReason reason, LinkedSlot source, Inventory target, SlotKey sourceKey) {
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

    /**
     * 双击收集: 按 COLLECT 优先级对全部参与Inventory各规划一份收取方案, 合成一笔事务提交;
     * 多个窗口槽背后是同一物理槽时只收一次.
     *
     * @param context 当前 Window 交互上下文
     */
    private static void collectToCursor(Context context) {
        ItemStack cursor = context.cursor();
        int space = cursor.getMaxStackSize() - cursor.getAmount();
        if (space <= 0) {
            return;
        }
        UpdateReason reason = reasonOf(context, ClickType.DOUBLE_CLICK);
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

    /**
     * 为这次点击构造变更原因, 随事务事件派发给观察者.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 点击类型
     * @return 以该玩家与点击类型组成的变更原因
     */
    private static UpdateReason reasonOf(Context context, ClickType clickType) {
        return new UpdateReason.PlayerClick(context.viewer(), clickType);
    }

    /**
     * 单槽点击的统一流程: 打开写规划 → 读槽内物品 → 算出点击结果 → 提交事务,
     * 提交成功后把新光标写回玩家; 任何一步不成立都只是把槽位标脏, 等同步纠偏.
     *
     * @param context 当前 Window 交互上下文
     * @param reason 变更原因
     * @param link 被点的Inventory槽
     * @param windowSlot 被点的窗口槽, 仅用于标脏
     * @param computation 左键或右键的具体算法
     */
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

    /**
     * 槽位与物品共同决定的有效堆叠上限: 两者取小.
     *
     * @param slotLimit 槽位堆叠上限
     * @param item 物品
     * @return 有效堆叠上限
     */
    private static int effectiveLimit(int slotLimit, ItemStack item) {
        return Math.min(slotLimit, item.getMaxStackSize());
    }

    /**
     * 拖拽分配时某个目标槽的容量基准: 槽位上限与物品自身上限取小,
     * 再减去槽内已有数量才是还能装的数量.
     *
     * @param link 目标Inventory槽
     * @param item 要放入的物品
     * @return 容量基准
     */
    private static int effectiveCapacity(LinkedSlot link, ItemStack item) {
        return Math.min(link.inventory().slotMaxStackSize(link.slot()), item.getMaxStackSize());
    }

    /**
     * 从光标物品里取走一部分后剩下的部分;
     * 取光了就是空物品.
     *
     * @param cursor 当前光标物品
     * @param taken 取走的数量
     * @return 剩余的光标物品
     */
    @NotNull
    private static ItemStack remainderOf(ItemStack cursor, int taken) {
        int left = cursor.getAmount() - taken;
        return left > 0 ? ItemUtils.copyWithAmount(cursor, left) : ItemStack.empty();
    }

    /**
     * 快速转移的候选目标: 全部参与Inventory排除源Inventory后, 按 ADD 优先级从高到低排序,
     * 优先级相同保持显示顺序.
     *
     * @param context 当前 Window 交互上下文
     * @param exclude 要排除的源Inventory, 不排除传 {@code null}
     * @return 候选目标Inventory列表
     */
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

    /**
     * 查出这个连接槽的最终物理身份, 用来识别 "两个窗口槽背后其实是同一块存储".
     *
     * @param link Inventory槽连接
     * @return 该槽的物理身份
     */
    private static SlotKey physicalKey(LinkedSlot link) {
        return ((SparrowInventory) link.inventory()).physicalKey(link.slot());
    }

    /**
     * 把一批窗口槽全部标脏, 让客户端在下一次同步时回到服务端状态.
     *
     * @param context 当前 Window 交互上下文
     * @param windowSlots 要标脏的窗口槽
     */
    private static void markAllDirty(Context context, List<Integer> windowSlots) {
        for (int i = 0; i < windowSlots.size(); i++) {
            context.markDirty(windowSlots.get(i));
        }
    }

    /**
     * 单槽点击的计算结果: 槽位新值与光标新值.
     *
     * @param slotAfter 点击后槽位的内容, {@code null} 表示槽位变空
     * @param cursorAfter 点击后光标的内容
     */
    private record SlotOutcome(@Nullable ItemStack slotAfter, @NotNull ItemStack cursorAfter) {
    }

    /**
     * 左右键各不相同的"点击后槽位与光标该变成什么"的入口.
     */
    private interface SlotComputation {

        /**
         * 根据槽内现状与槽位上限计算点击结果.
         *
         * @param current 槽内现有物品, 空槽为 {@code null}
         * @param slotLimit 槽位堆叠上限
         * @return 点击结果; {@code null} 表示无操作
         */
        @Nullable
        SlotOutcome compute(@Nullable ItemStack current, int slotLimit);
    }

    /**
     * 拖拽分配中一个还能接收物品的槽位.
     *
     * @param link 槽位连接
     * @param current 槽内现有物品, 空槽为 {@code null}
     * @param capacity 还能接纳的数量
     */
    private record DragTarget(@NotNull LinkedSlot link, @Nullable ItemStack current, int capacity) {
    }
}
