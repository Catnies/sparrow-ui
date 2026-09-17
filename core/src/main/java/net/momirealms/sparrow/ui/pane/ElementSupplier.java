package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

@FunctionalInterface
public interface ElementSupplier {

    /**
     * 给选中顺序里的第 occurrence 个槽位造一个元素.
     *
     * @param slots 本次要填充的完整槽位选择
     * @param occurrence 当前槽位在选中顺序里的序号
     * @return 这个槽位放什么, <strong>不能返回 null</strong>
     */
    @NotNull
    Element get(@NotNull SlotSequence slots, int occurrence);

    /**
     * 每个选中槽位都用同一个元素.
     *
     * @param element 共用元素
     * @return 固定元素生成器
     */
    @NotNull
    static ElementSupplier fixed(@NotNull Element element) {
        return (ignoredSize, ignoredOccurrence) -> element;
    }

    /**
     * 每个选中槽位现调一次 supplier.
     *
     * @param supplier 元素来源
     * @return 槽位元素生成器
     */
    @NotNull
    static ElementSupplier fromSupplier(@NotNull Supplier<? extends Element> supplier) {
        return (ignoredSize, ignoredOccurrence) -> supplier.get();
    }

    /**
     * 每个选中槽位现取一个 Item, 再包成元素.
     *
     * @param supplier Item 来源
     * @return Item 元素生成器
     */
    @NotNull
    static ElementSupplier items(@NotNull Supplier<? extends Item> supplier) {
        return (ignoredSize, ignoredOccurrence) -> new Element.Item(supplier.get());
    }

    /**
     * 把 Inventory 的内容按选中顺序循环铺进去, 第 n 个槽位接 Inventory 的第 {@code n % size} 格.
     *
     * <p>Inventory 是空的时候, 所有槽位都是空的.
     *
     * @param inventory 要铺进去的 Inventory
     * @return 逐槽连接 Inventory 的元素来源
     */
    @NotNull
    static ElementSupplier inventory(@NotNull SparrowInventory inventory) {
        int inventorySize = inventory.size();
        if (inventorySize == 0) {
            return fixed(Element.Empty.INSTANCE);
        }
        return (ignoredSize, occurrence) -> new Element.InventoryLink(inventory, occurrence % inventorySize);
    }

    /**
     * 把选中区域按原来的二维形状接到子 Pane 上, 从子 Pane 的左上角开始.
     *
     * @param pane 子 Pane
     * @return Pane 连接生成器
     */
    @NotNull
    static ElementSupplier pane(@NotNull Pane pane) {
        return pane(pane, 0, 0);
    }

    /**
     * 把选中区域按原来的二维形状接到子 Pane 的指定偏移处.
     *
     * @param pane 子 Pane
     * @param offsetX 子 Pane 内的横向偏移
     * @param offsetY 子 Pane 内的纵向偏移
     * @return Pane 连接生成器
     * @throws IndexOutOfBoundsException 选中区域探出子 Pane 时, 在生成元素那一步抛出
     */
    @NotNull
    static ElementSupplier pane(@NotNull Pane pane, int offsetX, int offsetY) {
        return (slots, occurrence) -> {
            // 保持选中区域的二维形状
            int childX = slots.xAt(occurrence) - slots.minX() + offsetX;
            int childY = slots.yAt(occurrence) - slots.minY() + offsetY;
            int childSlot = pane.size().indexOf(childX, childY);
            return Element.PaneLink.trusted(pane, childSlot);
        };
    }
}
