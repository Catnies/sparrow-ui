package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public sealed interface Element permits Element.Empty, Element.Item, Element.PaneLink, Element.InventoryLink {

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
     * 创建连接到子 Pane 槽位的元素.
     *
     * @param pane 子 Pane
     * @param slot 子 Pane 的槽位编号
     * @return Pane 连接元素
     */
    @NotNull
    static PaneLink pane(@NotNull Pane pane, int slot) {
        return new PaneLink(pane, slot);
    }

    /**
     * 创建连接到 Inventory 槽位的元素.
     *
     * @param inventory Inventory
     * @param slot Inventory 槽位编号
     * @return Inventory 连接元素
     */
    @NotNull
    static InventoryLink inventory(@NotNull SparrowInventory inventory, int slot) {
        return new InventoryLink(inventory, slot);
    }

    /**
     * 空槽位. Window 会在这里停止寻找 Item, 必要时显示 Pane 背景.
     */
    enum Empty implements Element {
        INSTANCE
    }

    /**
     * 直接显示并接收点击的 Item.
     *
     * @param item 要显示的 Item
     */
    record Item(@NotNull net.momirealms.sparrow.ui.item.Item item) implements Element {

        public Item {
            Objects.requireNonNull(item);
        }
    }

    /**
     * 把当前槽位连接到另一个 Pane 的指定槽位.
     */
    final class PaneLink implements Element {
        private final Pane pane;  // 子 Pane
        private final int slot; // 子 Pane 槽位编号

        /**
         * 创建到子 Pane 槽位的连接.
         *
         * @param pane 子 Pane
         * @param slot 子 Pane 槽位编号
         * @throws IndexOutOfBoundsException 槽位编号超出子 Pane 范围时抛出
         */
        public PaneLink(@NotNull Pane pane, int slot) {
            this.pane = pane;
            this.slot = pane.size().checkSlot(slot);
        }

        // 创建到子 Pane 槽位的连接, 跳过边界检查, 调用方必须保证 slot 已校验.
        private PaneLink(Pane pane, int slot, boolean trusted) {
            this.pane = pane;
            this.slot = slot;
        }

        // 创建到子 Pane 槽位的连接, 跳过重复边界检查.
        static PaneLink trusted(Pane pane, int slot) {
            return new PaneLink(pane, slot, true);
        }

        @NotNull
        public Pane pane() {
            return this.pane;
        }

        public int slot() {
            return this.slot;
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof PaneLink other && this.pane == other.pane && this.slot == other.slot;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this.pane) * 31 + this.slot;
        }
    }

    /**
     * 把当前 Pane 槽位连接到 Inventory 的指定槽位.
     *
     * @param inventory Inventory
     * @param slot Inventory 槽位编号
     */
    record InventoryLink(@NotNull SparrowInventory inventory, int slot) implements Element {

        public InventoryLink {
            Objects.requireNonNull(inventory);
            Objects.checkIndex(slot, inventory.size());
        }
    }
}
