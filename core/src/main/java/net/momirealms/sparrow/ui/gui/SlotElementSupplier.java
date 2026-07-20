package net.momirealms.sparrow.ui.gui;

import net.momirealms.sparrow.ui.item.Item;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * 为一组已选槽位逐个创建槽位元素.
 *
 * <p>常用于给 Structure 中同一个标志符出现的所有槽位填充内容.</p>
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
    @NotNull SlotElement get(@NotNull SlotSequence slots, int occurrence);

    /**
     * 让每个选中槽位共用同一个元素.
     *
     * @param element 共用元素
     * @return 固定元素生成器
     */
    static @NotNull SlotElementSupplier fixed(@NotNull SlotElement element) {
        return (_, _) -> element;
    }

    /**
     * 为每个选中槽位调用一次 Supplier.
     *
     * @param supplier 元素来源
     * @return 槽位元素生成器
     */
    static @NotNull SlotElementSupplier fromSupplier(@NotNull Supplier<? extends SlotElement> supplier) {
        return (_, _) -> supplier.get();
    }

    /**
     * 为每个选中槽位创建一个 Item 元素.
     *
     * @param supplier Item 来源
     * @return Item 元素生成器
     */
    static @NotNull SlotElementSupplier items(@NotNull Supplier<? extends Item> supplier) {
        return (_, _) -> new SlotElement.Item(supplier.get());
    }

    /**
     * 按原有二维形状把选中槽位连接到子 GUI.
     *
     * @param gui 子 GUI
     * @return GUI 连接生成器
     */
    static @NotNull SlotElementSupplier gui(@NotNull Gui gui) {
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
    static @NotNull SlotElementSupplier gui(@NotNull Gui gui, int offsetX, int offsetY) {
        return (slots, occurrence) -> {
            int childX = slots.xAt(occurrence) - slots.minX() + offsetX;
            int childY = slots.yAt(occurrence) - slots.minY() + offsetY;
            int childSlot = gui.size().indexOf(childX, childY);
            return SlotElement.GuiLink.trusted(gui, childSlot);
        };
    }
}
