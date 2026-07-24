package net.momirealms.sparrow.ui.gui;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 描述一个 GUI 槽位放什么.
 * <p>槽位可以是空的, 显示一个 Item, 或连接到另一个 GUI 的槽位.
 */
public sealed interface SlotElement permits SlotElement.Empty, SlotElement.Item, SlotElement.GuiLink {

    /**
     * 返回空槽位元素.
     *
     * @return 空槽位单例
     */
    static @NotNull Empty empty() {
        return Empty.INSTANCE;
    }

    /**
     * 创建直接显示指定 Item 的槽位元素.
     *
     * @param item 要显示的 Item
     * @return Item 槽位元素
     */
    static @NotNull Item item(@NotNull net.momirealms.sparrow.ui.item.Item item) {
        return new Item(item);
    }

    /**
     * 创建连接到子 GUI 槽位的元素.
     *
     * @param gui 子 GUI
     * @param slot 子 GUI 的槽位编号
     * @return GUI 连接元素
     */
    static @NotNull GuiLink gui(@NotNull Gui gui, int slot) {
        return new GuiLink(gui, slot);
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
        private final Gui gui;
        private final int slot;

        /**
         * 创建到子 GUI 槽位的连接.
         *
         * @param gui 子 GUI
         * @param slot 子 GUI 槽位编号
         */
        public GuiLink(@NotNull Gui gui, int slot) {
            this.gui = gui;
            this.slot = gui.size().checkSlot(slot);
        }

        // trusted 参数仅用于区分签名; 此构造器跳过 checkSlot, 调用方必须保证 slot 已校验
        private GuiLink(Gui gui, int slot, boolean trusted) {
            this.gui = gui;
            this.slot = slot;
        }

        public @NotNull Gui gui() {
            return this.gui;
        }

        public int slot() {
            return this.slot;
        }

        // 子槽位已由 GuiSize.indexOf 完成边界检查, 跳过重复校验
        static GuiLink trusted(Gui gui, int slot) {
            return new GuiLink(gui, slot, true);
        }
    }
}
