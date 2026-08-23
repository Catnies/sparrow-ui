package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.InventoryBundleSelectEvent;
import net.momirealms.sparrow.ui.inventory.event.SparrowInventoryClickEvent;
import net.momirealms.sparrow.ui.inventory.storage.SlotKey;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.util.ItemUtils;
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
 * 将 Window 上的点击或拖拽转换为 Inventory 事务.
 * <p>{@link Context} 提供当前状态, {@link InteractionGate} 连接事件派发.
 */
public final class ClickSemantics {

    private ClickSemantics() {
    }

    // Window 槽位当前连接的 Inventory 槽位.
    public record LinkedSlot(@NotNull SparrowInventory inventory, int slot) {
        SlotKey physicalKey() {
            return this.inventory.physicalKey(this.slot);
        }
    }

    // 参与交互的 Inventory 及其可见逻辑槽位.
    public record LinkedInventory(@NotNull SparrowInventory inventory, @NotNull BitSet visibleSlots) {
        boolean visible(int slot) {
            return this.visibleSlots.get(slot);
        }
    }

    /**
     * 根据当前点击 Context 推导出正确的 Paper InventoryAction.
     * 已识别但没有效果的操作返回 {@link InventoryAction#NOTHING}.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 已解析的点击类型
     * @param hotbarButton NUMBER_KEY 的热键编号, 其他点击传 {@code -1}
     * @param windowSlot 协议槽位(raw slot), 或 {@link InventoryView#OUTSIDE}
     * @return 按当前只读状态预估的 Bukkit 操作
     */
    @NotNull
    public static InventoryAction estimateInventoryAction(
            @NotNull Context context,
            @NotNull ClickType clickType,
            int hotbarButton,
            int windowSlot
    ) {
        // 只读规划不刷新外部存储, 也不保留候选.
        return ClickPlanner.prepareClick(context, clickType, hotbarButton, windowSlot, null, -1, () -> {}, false, InteractionOverlay.forClick()).action();
    }

    /**
     * 处理一次已经解析好的单击.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 已解析的点击类型
     * @param hotbarButton NUMBER_KEY 的热键编号, 其他点击传 {@code -1}
     * @param windowSlot 协议槽位(raw slot)
     * @return 语义已接管返回 {@code true}. 装饰性 Item 或空槽返回 {@code false}
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
     * <p>语义接管且参与者启用了 Bukkit 事件时, {@link InteractionGate#allowClick} 会被调用一次,
     * 即使这次点击没有候选. 冻结槽不会进入闸门.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 已解析的点击类型
     * @param hotbarButton NUMBER_KEY 的热键编号, 其他点击传 {@code -1}
     * @param windowSlot 协议槽位(raw slot)
     * @param observedBundle 记录选择时客户端看到的 Bundle, 没有选择时为 {@code null}
     * @param selectedIndex 记录的 Bundle 内部索引, 没有选择时为 {@code -1}
     * @param afterCommit 右键事务提交后清理 Window 选择状态的回调
     * @param gate 派发事件并复核 Window 状态的交互闸门
     * @return 语义已接管返回 {@code true}, 交给 Item 分派时返回 {@code false}
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

    // Sparrow 点击事件与 Bukkit 事件共享同一写入句柄.
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
        if (!inventory.hasClickObservers()) {
            return true;
        }
        SparrowInventoryClickEvent event = new SparrowInventoryClickEvent(inventory, slot, player, clickType, hotbarButton, action, edits);
        inventory.publishClick(event);
        return !event.cancelled();
    }

    @ApiStatus.Internal
    public static void dispatchBundleSelectEvent(@NotNull SparrowInventory inventory, int slot, @NotNull BundleSelectClick select) {
        inventory.publishBundleSelect(
                new InventoryBundleSelectEvent(inventory, slot, select.player(), select.window(), select.windowSlot(), select.bundleSlot())
        );
    }

    /**
     * 处理窗口外点击.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 点击类型(WINDOW_BORDER_LEFT 丢整堆, 其余丢一个)
     */
    public static void handleOutsideClick(@NotNull Context context, @NotNull ClickType clickType) {
        ClickExecutor.handleOutsideClick(context, clickType);
    }

    /**
     * 处理一次已经完成的拖拽分配. 所有碰到的当前 Inventory 槽位进入同一笔事务, 分不完的部分留在光标上.
     * <p>拖拽经过的 Item 槽, 空槽和冻结槽不参与分配, 也不会出现在事件的分配结果里. 整趟拖拽全落在
     * 这些槽位上时本方法不派发任何事件.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 拖拽按键(LEFT 均分, RIGHT 每槽一个, MIDDLE 创造模式每槽整堆且不消耗光标)
     * @param windowSlots 拖拽经过的全部 Window 槽位
     */
    public static void handleDrag(@NotNull Context context, @NotNull ClickType clickType, @NotNull List<Integer> windowSlots) {
        ClickExecutor.handleDrag(context, clickType, windowSlots, InteractionGate.ALLOW_ALL);
    }

    @ApiStatus.Internal
    public static void handleDrag(@NotNull Context context, @NotNull ClickType clickType, @NotNull List<Integer> windowSlots, @NotNull InteractionGate gate) {
        ClickExecutor.handleDrag(context, clickType, windowSlots, gate);
    }

    @ApiStatus.Internal
    public interface Context {

        @NotNull
        Player viewer();

        /**
         * 查出 Window 槽位当前连接的 Inventory 槽位.
         *
         * @param windowSlot Window 槽位
         * @return 连接的 Inventory 槽位, 没有连接时为 {@code null}
         */
        @Nullable
        LinkedSlot linkAt(int windowSlot);

        /**
         * 判断 Window 槽位的显示路径是否经过冻结 Pane.
         *
         * @param windowSlot Window 槽位
         * @return 路径经过已冻结 Pane 时返回 {@code true}
         */
        boolean frozenAt(int windowSlot);

        /**
         * 判断某个 Window 槽位此刻渲染出来是不是一格空位.
         * <p>以最终显示结果为准, 背景和占位物品都算非空.
         *
         * @param windowSlot Window 槽位
         * @return 玩家看到的是一格空位时返回 {@code true}
         */
        boolean displayedEmptyAt(int windowSlot);

        /**
         * 查出数字键对应的 lower 快捷栏 Inventory 槽位.
         *
         * @param hotbarButton 热键编号, 0 到 8
         * @return 连接的 Inventory 槽位, 不可交互时为 {@code null}
         */
        @Nullable
        LinkedSlot hotbarLink(int hotbarButton);

        /**
         * 按显示顺序列出参与交互的 Inventory 及其未冻结可见槽位.
         * <p>快速转移与双击收集只在这些槽位中选择目标.
         *
         * @return 参与语义的全部 Inventory 及可见槽位
         */
        @NotNull
        List<LinkedInventory> linkedInventories();

        /**
         * 返回光标物品副本, 空光标使用空物品而不是 {@code null}.
         *
         * @return 光标物品副本
         */
        @NotNull
        ItemStack cursor();

        /**
         * 返回光标物品的底层对象, 不复制也不包装.
         *
         * @return 光标物品的 NMS 句柄
         */
        @NotNull
        default Object unsafeCursor() {
            return ItemUtils.getItemStackHandle(this.cursor());
        }

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
         * 标记某个 Window 槽位需要重新核对. 下一次同步时客户端会被纠正为服务端渲染结果,
         * 用来纠正客户端的点击预测.
         *
         * @param windowSlot Window 槽位
         */
        void markDirty(int windowSlot);
    }

    /**
     * 候选形成后, 事务提交前依次经过的交互闸门.
     * <p>语义引擎在每次派发前后都会自己复核 {@link #stillValid()} 并重新校验候选,
     * 实现只负责派发事件本身, 不需要重复检查 Window 状态.
     */
    @ApiStatus.Internal
    public interface InteractionGate {
        InteractionGate ALLOW_ALL = new InteractionGate() {
        };

        /**
         * 派发 Bukkit 点击事件. 参与者启用了 Bukkit 事件时调用, 与是否算出候选无关. 冻结槽不会调用.
         * <p>没有候选时这次调用没有事务可以取消, 返回 {@code false} 只会让 Window 走一次全量恢复.
         * 通过 {@code edits} 写入的内容仍会组成一笔事务提交.
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
         * @param newItems 候选提交后的协议槽位内容, 已经过放入规则过滤和重新分配.
         *                 只包含背后有 Inventory 且未冻结的槽位, 同一趟拖拽经过的 Item 槽, 空槽和冻结槽不会出现在这里
         * @param edits 把事件写入合并进本次候选草稿的句柄
         * @return 事件没有被取消时返回 {@code true}
         */
        default boolean allowDrag(@NotNull ItemStack newCursor, @NotNull Map<Integer, ItemStack> newItems, @NotNull InteractionEdits edits) {
            return true;
        }

        /**
         * 返回 {@link #allowClick} 与 {@link #allowDrag} 是否实际派发 Bukkit 事件.
         * 返回 {@code false} 表示那一段只是放行, 中途没有任何用户代码跑过, 引擎据此省掉闸门之后的外部容器重同步.
         *
         * @return 本次交互会派发 Bukkit 事件时返回 {@code true}
         */
        default boolean firesBukkitEvents() {
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
