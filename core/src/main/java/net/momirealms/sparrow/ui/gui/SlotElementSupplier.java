package net.momirealms.sparrow.ui.gui;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * 为一组已选槽位逐个创建槽位元素.
 * <p>用于给 Structure 中同一个标志符出现的所有槽位填充内容.
 */
@FunctionalInterface
public interface SlotElementSupplier {

    /**
     * 为当前选中槽位创建元素.
     *
     * @param slots 本次要填充的完整槽位选择
     * @param occurrence 当前槽位在选择中的序号
     * @return 非 null 槽位元素
     */
    @NotNull
    SlotElement get(@NotNull SlotSequence slots, int occurrence);

    /**
     * 让每个选中槽位共用同一个元素.
     *
     * @param element 共用元素
     * @return 固定元素生成器
     */
    @NotNull
    static SlotElementSupplier fixed(@NotNull SlotElement element) {
        return (ignoredSize, ignoredOccurrence) -> element;
    }

    /**
     * 为每个选中槽位调用一次 Supplier.
     *
     * @param supplier 元素来源
     * @return 槽位元素生成器
     */
    @NotNull
    static SlotElementSupplier fromSupplier(@NotNull Supplier<? extends SlotElement> supplier) {
        return (ignoredSize, ignoredOccurrence) -> supplier.get();
    }

    /**
     * 为每个选中槽位创建一个 Item 元素.
     *
     * @param supplier Item 来源
     * @return Item 元素生成器
     */
    @NotNull
    static SlotElementSupplier items(@NotNull Supplier<? extends Item> supplier) {
        return (ignoredSize, ignoredOccurrence) -> new SlotElement.Item(supplier.get());
    }

    /**
     * 把库存按 ingredient 出现顺序循环铺入: 第 n 次出现(从 0 开始)的槽位连接库存槽
     * {@code n % inventory.size()}. 零尺寸库存生成空槽位.
     *
     * @param inventory 要铺入的库存
     * @return 逐槽连接库存的元素来源
     */
    @NotNull
    static SlotElementSupplier inventory(@NotNull SparrowInventory inventory) {
        int inventorySize = inventory.size();
        if (inventorySize == 0) {
            return fixed(SlotElement.Empty.INSTANCE);
        }
        return (ignoredSize, occurrence) -> new SlotElement.InventoryLink(inventory, occurrence % inventorySize);
    }

    /**
     * 按原有二维形状把选中槽位连接到子 GUI.
     *
     * @param gui 子 GUI
     * @return GUI 连接生成器
     */
    @NotNull
    static SlotElementSupplier gui(@NotNull Gui gui) {
        return gui(gui, 0, 0);
    }

    /**
     * 按原有二维形状把选中槽位连接到子 GUI 的指定偏移位置.
     *
     * @param gui 子 GUI
     * @param offsetX 子 GUI 内的横向偏移
     * @param offsetY 子 GUI 内的纵向偏移
     * @return GUI 连接生成器
     */
    @NotNull
    static SlotElementSupplier gui(@NotNull Gui gui, int offsetX, int offsetY) {
        return (slots, occurrence) -> {
            // 选中区域保持原有二维形状, 相对坐标加偏移后映射到子 GUI 槽位
            int childX = slots.xAt(occurrence) - slots.minX() + offsetX;
            int childY = slots.yAt(occurrence) - slots.minY() + offsetY;
            int childSlot = gui.size().indexOf(childX, childY);
            // 子槽位刚由 indexOf 完成边界检查, 使用免校验构造
            return SlotElement.GuiLink.trusted(gui, childSlot);
        };
    }
}
