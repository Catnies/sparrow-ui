package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.inventory.event.InventoryBundleSelectEvent;
import net.momirealms.sparrow.ui.inventory.event.SparrowInventoryClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * 点击语义的对外入口: 把玩家在 Window 上的一次点击或拖拽, 翻译成 Inventory 上的一笔事务.
 * <p>Window 只负责把解析好的点击交进来, 由这里决定这次交互接管不接管, 会改哪些槽位,
 * 以及沿途该派发哪些事件. 引擎接管不了的槽位(装饰 Item, 空槽)会被交还给 Window 自己分派.
 * <p>调用方通过 {@link Context} 提供 Window 的当前状态, 通过 {@link InteractionGate} 接管事件派发;
 * 不需要事件的调用方直接用不带闸门的重载.
 */
public final class ClickSemantics {

    private ClickSemantics() {
    }

    // 一个 Window 槽位背后连接的当前 Inventory 槽位, 可计算连接最终指向的 SlotKey.
    public record LinkedSlot(@NotNull SparrowInventory inventory, int slot) {
        SlotKey physicalKey() {
            return this.inventory.physicalKey(this.slot);
        }
    }

    // 参与点击语义的连接 Inventory 及其可见槽位: 只有经未冻结协议槽展示的槽位可见.
    public record LinkedInventory(@NotNull SparrowInventory inventory, @NotNull BitSet visibleSlots) {
        boolean visible(int slot) {
            return this.visibleSlots.get(slot);
        }
    }

    /**
     * 根据当前点击 Context 推导出正确的 Paper InventoryAction.
     * 其他已支持但点了没效果的操作返回 {@link InventoryAction#NOTHING}.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 已解析的点击类型
     * @param hotbarButton NUMBER_KEY 的热键编号, 其他点击传 {@code -1}
     * @param windowSlot 协议槽位(raw slot), 或 {@link InventoryView#OUTSIDE}
     * @return 按当前只读状态预估的 Bukkit 操作
     */
    @NotNull
    @ApiStatus.Internal
    public static InventoryAction estimateInventoryAction(
            @NotNull Context context,
            @NotNull ClickType clickType,
            int hotbarButton,
            int windowSlot
    ) {
        // 只取规划器算出的操作类型: write 传 false 表示全程走只读快照, 不同步外部容器, 也不留下候选.
        // 预估跑在任何交互事件之前, 现场上没有覆盖可言.
        return ClickPlanner.prepareClick(context, clickType, hotbarButton, windowSlot, null, -1, () -> {}, false, InteractionOverlay.forClick()).action();
    }

    /**
     * 处理一次已经解析好的单击.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 已解析的点击类型
     * @param hotbarButton NUMBER_KEY 的热键编号, 其他点击传 {@code -1}
     * @param windowSlot 协议槽位(raw slot)
     * @return 语义已接管返回 {@code true}; 否则表示点的是装饰性 Item 或空槽, 与Inventory无关
     *
     */
    public static boolean handleClick(
            @NotNull Context context,
            @NotNull ClickType clickType,
            int hotbarButton,
            int windowSlot
    ) {
        return ClickExecutor.handleClick(context, clickType, hotbarButton, windowSlot, null, -1, () -> {}, InteractionGate.ALLOW_ALL);
    }

    /**
     * 处理带 Window 本地 Bundle 选择状态与交互闸门的单击.
     * <p>只要语义接管了这个槽位, {@link InteractionGate#allowClick} 一律会被调用一次,
     * 即使这次点击算不出候选(冻结槽, 空操作, 被放入规则拒绝). 其余闸门方法仍然只在候选存在时调用.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 已解析的点击类型
     * @param hotbarButton NUMBER_KEY 的热键编号, 其他点击传 {@code -1}
     * @param windowSlot 协议槽位(raw slot)
     * @param observedBundle 记录选择时客户端看到的 Bundle, 没有选择时为 {@code null}
     * @param selectedIndex 记录的 Bundle 内部索引, 没有选择时为 {@code -1}
     * @param afterCommit 右键事务提交后清理 Window 选择状态的回调
     * @param gate 派发事件并复核 Window 状态的交互闸门
     * @return 语义已接管返回 {@code true}; {@code false} 表示交给 Item 分派
     */
    @ApiStatus.Internal
    public static boolean handleClick(
            @NotNull Context context,
            @NotNull ClickType clickType,
            int hotbarButton,
            int windowSlot,
            @Nullable ItemStack observedBundle,
            int selectedIndex,
            @NotNull Runnable afterCommit,
            @NotNull InteractionGate gate
    ) {
        return ClickExecutor.handleClick(context, clickType, hotbarButton, windowSlot, observedBundle, selectedIndex, afterCommit, gate);
    }

    /**
     * 在候选形成后, 事务 Pre 前向被 InventoryLink 直接连接的 Inventory 派发点击事件.
     *
     * @param edits 把事件写入合并进本次候选草稿的句柄, 与前一道 Bukkit 事件用的是同一份
     * @return 事件没有被取消时返回 {@code true}
     */
    @ApiStatus.Internal
    public static boolean dispatchClickEvent(
            @NotNull SparrowInventory inventory,
            int slot,
            @NotNull Player player,
            @NotNull ClickType clickType,
            int hotbarButton,
            @NotNull InventoryAction action,
            @NotNull InteractionEdits edits
    ) {
        // 没有订阅者时事件构造出来也无人可改, 直接放行; 引擎自己的复核不在这里, 短路不会跳过它们.
        if (!inventory.hasClickObservers()) {
            return true;
        }
        SparrowInventoryClickEvent event = new SparrowInventoryClickEvent(inventory, slot, player, clickType, hotbarButton, action, edits);
        inventory.publishClick(event);
        return !event.cancelled();
    }

    /**
     * 向被 InventoryLink 直接连接的 Inventory 派发 Bundle 选择事件.
     */
    @ApiStatus.Internal
    public static void dispatchBundleSelectEvent(@NotNull SparrowInventory inventory, int slot, @NotNull BundleSelectClick select) {
        inventory.publishBundleSelect(
                new InventoryBundleSelectEvent(inventory, slot, select.player(), select.window(), select.windowSlot(), select.bundleSlot())
        );
    }

    /**
     * 处理点到窗口外的点击
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 点击类型(WINDOW_BORDER_LEFT 丢整堆, 其余丢一个)
     */
    public static void handleOutsideClick(@NotNull Context context, @NotNull ClickType clickType) {
        ClickExecutor.handleOutsideClick(context, clickType);
    }

    /**
     * 处理一次已经完成的拖拽分配: 所有碰到的当前 Inventory 槽位进入同一笔事务, 分不完的部分留在光标上.
     * <p>拖拽经过的 Item 槽, 空槽和冻结槽不参与分配, 也不会出现在事件的分配结果里; 整趟拖拽全落在
     * 这些槽位上时本方法不派发任何事件.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 拖拽按键(LEFT 均分, RIGHT 每槽一个, MIDDLE 创造模式每槽整堆且不消耗光标)
     * @param windowSlots 拖拽经过的全部 Window 槽位
     */
    public static void handleDrag(@NotNull Context context, @NotNull ClickType clickType, @NotNull List<Integer> windowSlots) {
        ClickExecutor.handleDrag(context, clickType, windowSlots, InteractionGate.ALLOW_ALL);
    }

    /**
     * 处理带交互闸门的拖拽. 闸门接收规则过滤和重新分配后的最终光标与槽位候选.
     *
     * @param gate 派发事件并复核 Window 状态的交互闸门
     */
    @ApiStatus.Internal
    public static void handleDrag(@NotNull Context context, @NotNull ClickType clickType, @NotNull List<Integer> windowSlots, @NotNull InteractionGate gate) {
        ClickExecutor.handleDrag(context, clickType, windowSlots, gate);
    }

    @ApiStatus.Internal
    public interface Context {

        /**
         * 本次操作对应的玩家.
         *
         * @return 交互的玩家
         */
        @NotNull
        Player viewer();

        /**
         * 查出某个 Window 槽位背后连接的 Inventory 及其当前 Inventory 槽位;
         * Item 与空槽背后没有 Inventory, 返回 {@code null}.
         *
         * @param windowSlot Window 槽位
         * @return 连接的 Inventory 槽位, 没有连接时为 {@code null}
         */
        @Nullable
        LinkedSlot linkAt(int windowSlot);

        /**
         * 判断某个 Window 槽位的显示路径是否经过已冻结 Pane;
         * Pane 冻结槽不参与任何点击语义.
         *
         * @param windowSlot Window 槽位
         * @return 路径经过已冻结 Pane 时返回 {@code true}
         */
        boolean frozenAt(int windowSlot);

        /**
         * 判断某个 Window 槽位此刻渲染出来是不是一格空位.
         * <p>问的是最终显示结果, 因此不区分这一格空着的原因是没有内容, 还是内容被渲染成了空气;
         * 同样地, 背景与占位物品无论挂在哪一层, 只要玩家看得见就算这一格非空.
         *
         * @param windowSlot Window 槽位
         * @return 玩家看到的是一格空位时返回 {@code true}
         */
        boolean displayedEmptyAt(int windowSlot);

        /**
         * 查出数字键要交换的目标: 当前 lower 快捷栏某个按键位置实际连接的当前 Inventory 槽位;
         * 该位置不是 InventoryLink 或路径经过已冻结 Pane 时返回 {@code null}.
         *
         * @param hotbarButton 热键编号, 0 到 8
         * @return 连接的Inventory槽, 不可交互时为 {@code null}
         */
        @Nullable
        LinkedSlot hotbarLink(int hotbarButton);

        /**
         * 按显示顺序列出参与本次点击语义的全部Inventory(去重)及各自的可见槽位, 快速转移与双击收集只在可见槽位里找目标;
         * 只通过 Pane 冻结槽或 Window 虚拟槽位连接的 Inventory 不应包含在内, 已包含 Inventory 中
         * 未经任何未冻结协议槽展示的槽位不属于可见集.
         *
         * @return 参与语义的全部Inventory及可见槽位
         */
        @NotNull
        List<LinkedInventory> linkedInventories();

        /**
         * 光标上正拿着的物品副本;
         * 没拿东西时返回空物品而不是 {@code null}.
         *
         * @return 光标物品副本
         */
        @NotNull
        ItemStack cursor();

        /**
         * 直接覆盖菜单光标上的物品.
         *
         * @param cursor 新的光标物品
         */
        void cursor(@NotNull ItemStack cursor);

        /**
         * 副手物品的副本.
         *
         * @return 副手物品副本, 空副手为 {@code null}
         */
        @Nullable
        ItemStack offhand();

        /**
         * 直接覆盖玩家副手物品.
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
         * 标记某个 Window 槽位需要重新核对: 下一次同步时客户端会被纠正为服务端渲染结果,
         * 用来纠正客户端的点击预测.
         *
         * @param windowSlot Window 槽位
         */
        void markDirty(int windowSlot);
    }

    /**
     * 候选形成后、事务提交前依次经过的交互闸门.
     * <p>语义引擎在每次派发前后都会自己复核 {@link #stillValid()} 并重新校验候选,
     * 实现只负责派发事件本身, 不需要重复检查 Window 状态.
     */
    @ApiStatus.Internal
    public interface InteractionGate {

        // 一律放行的闸门, 供不派发事件的调用方使用.
        InteractionGate ALLOW_ALL = new InteractionGate() {
        };

        /**
         * 派发 Bukkit 点击事件. 语义接管的每一次点击都会调用一次, 与是否算出候选无关; 只有冻结槽完全不派发.
         * <p>没有候选时这次调用没有事务可以取消, 返回 {@code false} 只会让 Window 走一次全量恢复;
         * 但通过 {@code edits} 写入的内容照样会攒成一笔事务提交.
         *
         * @param action 本次点击对应的 Bukkit 操作, 没有候选时为实际估算出的操作
         * @param edits 把事件写入合并进本次交互草稿的句柄
         * @return 事件没有被取消时返回 {@code true}
         */
        default boolean allowClick(@NotNull InventoryAction action, @NotNull InteractionEdits edits) {
            return true;
        }

        /**
         * 向被 InventoryLink 直接连接的 Inventory 派发点击事件.
         * 拖拽候选没有单一事件目标, 不会调用本方法.
         *
         * @param link 候选的事件目标槽位
         * @param action 候选对应的 Bukkit 操作
         * @param edits 把事件写入合并进本次候选草稿的句柄, 与 Bukkit 事件用的是同一份
         * @return 事件没有被取消时返回 {@code true}
         */
        default boolean allowInventoryClick(@NotNull LinkedSlot link, @NotNull InventoryAction action, @NotNull InteractionEdits edits) {
            return true;
        }

        /**
         * 派发 Bukkit 拖拽事件. 拖拽经过的槽位里只要还剩一个引擎接管得了的, 就会派发一次.
         *
         * @param newCursor 候选提交后的光标物品
         * @param newItems 候选提交后的协议槽位内容, 已经过放入规则过滤和重新分配;
         *                 只包含背后有 Inventory 且未冻结的槽位, 同一趟拖拽经过的 Item 槽, 空槽和冻结槽不会出现在这里
         * @param edits 把事件写入合并进本次候选草稿的句柄
         * @return 事件没有被取消时返回 {@code true}
         */
        default boolean allowDrag(@NotNull ItemStack newCursor, @NotNull Map<Integer, ItemStack> newItems, @NotNull InteractionEdits edits) {
            return true;
        }

        /**
         * 复核交互是否仍然属于当前 Window 状态.
         * 事件处理器可能已经关闭或重开 Window, 改变菜单状态, 或替换协议槽位的连接与冻结语义.
         *
         * @return 交互仍然有效时返回 {@code true}
         */
        default boolean stillValid() {
            return true;
        }
    }
}
