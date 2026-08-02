package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.click.BundleSelectClick;
import net.momirealms.sparrow.ui.inventory.event.InventoryBundleSelectEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class ClickSemantics {

    private ClickSemantics() {
    }

    // 一个 Window 槽位背后连接的当前 Inventory 槽位, 可计算连接最终指向的 SlotKey.
    public record LinkedSlot(@NotNull SparrowInventory inventory, int slot) {
        SlotKey physicalKey() {
            return this.inventory.physicalKey(this.slot);
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
    public static InventoryAction estimateInventoryAction(
            @NotNull Context context,
            @NotNull ClickType clickType,
            int hotbarButton,
            int windowSlot
    ) {
        return ClickActionResolver.resolve(context, clickType, hotbarButton, windowSlot);
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
        return ClickExecutor.handleClick(context, clickType, hotbarButton, windowSlot, null, -1, () -> {});
    }

    /**
     * 处理带 Window 本地 Bundle 选择状态的单击.
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 已解析的点击类型
     * @param hotbarButton NUMBER_KEY 的热键编号, 其他点击传 {@code -1}
     * @param windowSlot 协议槽位(raw slot)
     * @param observedBundle 记录选择时客户端看到的 Bundle, 没有选择时为 {@code null}
     * @param selectedIndex 记录的 Bundle 内部索引, 没有选择时为 {@code -1}
     * @param afterCommit 右键事务提交后清理 Window 选择状态的回调
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
            @NotNull Runnable afterCommit
    ) {
        return ClickExecutor.handleClick(context, clickType, hotbarButton, windowSlot, observedBundle, selectedIndex, afterCommit);
    }

    /**
     * 在事务规划前向被 InventoryLink 直接连接的 Inventory 派发点击事件.
     *
     * @return 事件没有被取消时返回 {@code true}
     */
    @ApiStatus.Internal
    public static boolean dispatchClickEvent(
            @NotNull SparrowInventory inventory,
            int slot,
            @NotNull Player player,
            @NotNull ClickType clickType,
            int hotbarButton,
            @NotNull InventoryAction action
    ) {
        InventoryClickEvent event = new InventoryClickEvent(inventory, slot, player, clickType, hotbarButton, action);
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
     *
     * @param context 当前 Window 交互上下文
     * @param clickType 拖拽按键(LEFT 均分, RIGHT 每槽一个, MIDDLE 创造模式每槽整堆且不消耗光标)
     * @param windowSlots 拖拽经过的全部 Window 槽位
     */
    public static void handleDrag(@NotNull Context context, @NotNull ClickType clickType, @NotNull List<Integer> windowSlots) {
        ClickExecutor.handleDrag(context, clickType, windowSlots);
    }

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
         * 判断某个 Window 槽位的显示路径是否经过已冻结 GUI;
         * GUI 冻结槽不参与任何点击语义.
         *
         * @param windowSlot Window 槽位
         * @return 路径经过已冻结 GUI 时返回 {@code true}
         */
        boolean frozenAt(int windowSlot);

        /**
         * 查出数字键要交换的目标: 当前 lower 快捷栏某个按键位置实际连接的当前 Inventory 槽位;
         * 该位置不是 InventoryLink 或路径经过已冻结 GUI 时返回 {@code null}.
         *
         * @param hotbarButton 热键编号, 0 到 8
         * @return 连接的Inventory槽, 不可交互时为 {@code null}
         */
        @Nullable
        LinkedSlot hotbarLink(int hotbarButton);

        /**
         * 按显示顺序列出参与本次点击语义的全部Inventory(去重), 快速转移与双击收集在它们里面找目标;
         * 只通过 GUI 冻结槽或 Window 虚拟槽位连接的 Inventory 不应包含在内.
         *
         * @return 参与语义的全部Inventory
         */
        @NotNull
        List<SparrowInventory> linkedInventories();

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
}
