package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Pane 一个槽位里放的东西: 空着, 摆一个 Item, 或者接向子 Pane / Inventory 的某一格.
 * <p>{@link PaneLink} 和 {@link InventoryLink} 只是静态连接, 点下去最后落到谁手上由点击目标解析决定.
 */
public sealed interface Element permits Element.Empty, Element.Item, Element.PaneLink, Element.InventoryLink {

    /**
     * 空槽位元素, 一直是同一个单例.
     *
     * @return 空槽位
     */
    @NotNull
    static Empty empty() {
        return Empty.INSTANCE;
    }

    /**
     * 把一个 Item 摆到槽位上, 之后显示什么, 点击算谁的都归它.
     *
     * @param item 要显示的 Item
     * @return Item 槽位元素
     */
    @NotNull
    static Item item(@NotNull net.momirealms.sparrow.ui.item.Item item) {
        return new Item(item);
    }

    /**
     * 接向子 Pane 的某一格, 槽号当场在子 Pane 上校验.
     *
     * @param pane 子 Pane
     * @param slot 子 Pane 的槽位编号
     * @return Pane 连接元素
     * @throws IndexOutOfBoundsException 槽号超出子 Pane 范围时
     */
    @NotNull
    static PaneLink pane(@NotNull Pane pane, int slot) {
        return new PaneLink(pane, slot);
    }

    /**
     * 接向 Inventory 的某一格, 槽号当场在 Inventory 上校验.
     *
     * @param inventory Inventory
     * @param slot Inventory 槽位编号
     * @return Inventory 连接元素
     * @throws IndexOutOfBoundsException 槽号超出 Inventory 范围时
     */
    @NotNull
    static InventoryLink inventory(@NotNull SparrowInventory inventory, int slot) {
        return new InventoryLink(inventory, slot);
    }

    /**
     * 空槽位. 显示路径走到这里就到底了, 这一格显示 Pane 背景, 没有背景就空着.
     */
    enum Empty implements Element {
        INSTANCE
    }

    /**
     * 直接在槽位上显示, 也直接接点击的 Item.
     *
     * @param item 要显示的 Item
     */
    record Item(@NotNull net.momirealms.sparrow.ui.item.Item item) implements Element {

        public Item {
            Objects.requireNonNull(item);
        }
    }

    /**
     * 把当前槽位接到另一个 Pane 的某一格上.
     * <p>两个 PaneLink 相等, 当且仅当它们指着同一个 Pane 实例的同一格.
     */
    final class PaneLink implements Element {
        private final Pane pane;
        private final int slot;

        /**
         * 建连接, 顺便检查槽号在子 Pane 里.
         *
         * @param pane 子 Pane
         * @param slot 子 Pane 槽位编号
         * @throws IndexOutOfBoundsException 槽号超出子 Pane 范围时
         */
        public PaneLink(@NotNull Pane pane, int slot) {
            this.pane = pane;
            this.slot = pane.size().checkSlot(slot);
        }

        private PaneLink(Pane pane, int slot, boolean trusted) {
            this.pane = pane;
            this.slot = slot;
        }

        // 槽号调用方已经校验过, 这里不再查一遍边界
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
     * 把当前槽位接到 Inventory 的某一格上, 槽号超出范围就当场失败.
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
