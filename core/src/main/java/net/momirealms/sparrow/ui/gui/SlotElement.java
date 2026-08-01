package net.momirealms.sparrow.ui.gui;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public sealed interface SlotElement permits SlotElement.Empty, SlotElement.Item, SlotElement.GuiLink, SlotElement.InventoryLink {

    /**
     * 返回空槽位元素.
     *
     * @return 空槽位单例
     */
    @NotNull
    static Empty empty() {
        return Empty.INSTANCE;
    }

    /**
     * 创建直接显示指定 Item 的槽位元素.
     *
     * @param item 要显示的 Item
     * @return Item 槽位元素
     */
    @NotNull
    static Item item(@NotNull net.momirealms.sparrow.ui.item.Item item) {
        return new Item(item);
    }

    /**
     * 创建连接到子 GUI 槽位的元素.
     *
     * @param gui 子 GUI
     * @param slot 子 GUI 的槽位编号
     * @return GUI 连接元素
     */
    @NotNull
    static GuiLink gui(@NotNull Gui gui, int slot) {
        return new GuiLink(gui, slot);
    }

    /**
     * 创建连接到库存槽位的元素.
     *
     * @param inventory 库存
     * @param slot 库存槽位编号
     * @return 库存连接元素
     */
    @NotNull
    static InventoryLink inventory(@NotNull SparrowInventory inventory, int slot) {
        return new InventoryLink(inventory, slot);
    }

    /**
     * 空槽位. Window 会在这里停止寻找 Item, 必要时显示 GUI 背景.
     */
    enum Empty implements SlotElement {
        INSTANCE
    }

    /**
     * 直接显示并接收点击的 Item.
     *
     * @param item 要显示的 Item
     */
    record Item(@NotNull net.momirealms.sparrow.ui.item.Item item) implements SlotElement {

        public Item {
            Objects.requireNonNull(item);
        }
    }

    /**
     * 把当前槽位连接到另一个 GUI 的指定槽位.
     */
    final class GuiLink implements SlotElement {
        private final Gui gui;  // 子 GUI
        private final int slot; // 子 GUI 槽位编号

        /**
         * 创建到子 GUI 槽位的连接.
         *
         * @param gui 子 GUI
         * @param slot 子 GUI 槽位编号
         * @throws IndexOutOfBoundsException 槽位编号超出子 GUI 范围时抛出
         */
        public GuiLink(@NotNull Gui gui, int slot) {
            this.gui = gui;
            this.slot = gui.size().checkSlot(slot);
        }

        /**
         * 创建到子 GUI 槽位的连接, 跳过边界检查.
         *
         * @param gui 子 GUI
         * @param slot 子 GUI 槽位编号, 调用方必须保证已校验
         * @param trusted 仅用于区分签名, 无实际含义
         */
        private GuiLink(Gui gui, int slot, boolean trusted) {
            this.gui = gui;
            this.slot = slot;
        }

        @NotNull
        public Gui gui() {
            return this.gui;
        }

        public int slot() {
            return this.slot;
        }

        /**
         * 创建到子 GUI 槽位的连接, 跳过重复边界检查.
         *
         * @param gui 子 GUI
         * @param slot 已校验的子 GUI 槽位编号
         * @return GUI 连接元素
         */
        static GuiLink trusted(Gui gui, int slot) {
            return new GuiLink(gui, slot, true);
        }
    }

    /**
     * 把当前 GUI 槽位连接到 Inventory 的指定槽位: 显示该槽的当前内容, 内容随 Inventory 事务刷新.
     *
     * @param inventory 库存
     * @param slot 库存槽位编号
     */
    record InventoryLink(@NotNull SparrowInventory inventory, int slot) implements SlotElement {

        public InventoryLink {
            Objects.requireNonNull(inventory);
            Objects.checkIndex(slot, inventory.size());
        }
    }
}
