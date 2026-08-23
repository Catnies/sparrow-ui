package net.momirealms.sparrow.ui.inventory.storage;

import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link ReferencingInventory} 的权威内容存储, 槽位使用存储自身坐标.
 * <p><strong>同一 Inventory 的所有访问必须由调用方串行执行</strong>.
 */
@ApiStatus.Experimental
public interface ExternalStorage {

    /**
     * 封装一个 NMS 容器, 使用它的槽位数量, 堆叠上限和读写规则.
     *
     * @param container {@code net.minecraft.world.Container} 实例
     * @return 该容器的外部存储
     */
    @NotNull
    static ExternalStorage ofContainer(@NotNull Object container) {
        return ContainerStorage.of(container);
    }

    /**
     * 存储的槽位数量, <strong>构造后不得变化</strong>.
     *
     * @return 槽位数量
     */
    int size();

    /**
     * 读取槽位现值.
     * <p>可以返回内部活实例或副本. <strong>引擎只读取, 且不持有到本次操作之外</strong>.
     *
     * @param slot 存储槽位
     * @return 槽位现值, 空槽返回 {@code null}
     */
    @Nullable
    ItemStack read(int slot);

    /**
     * 批量读取全部槽位现值, 供逐槽对比与规划基准构建.
     * <p>数组归调用方本次使用, 元素契约与 {@link #read(int)} 相同. 默认实现逐槽读取.
     *
     * @return 按存储槽位排列的现值数组, 空槽为 {@code null}
     */
    default @Nullable ItemStack @NotNull [] readAll() {
        @Nullable ItemStack[] contents = new ItemStack[this.size()];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = this.read(slot);
        }
        return contents;
    }

    /**
     * 写入槽位内容.
     * <p>传入实例的所有权交给存储, <strong>调用方不得继续修改或持有</strong>.
     *
     * @param slot 存储槽位
     * @param item 新内容, 清空槽位为 {@code null}
     */
    void write(int slot, @Nullable ItemStack item);

    /**
     * 判断槽位现值与期望内容是否相同.
     * <p>刷新会逐槽调用本方法, 实现应尽量在底层表示上比较并避免分配对象.
     *
     * @param slot 存储槽位
     * @param expected 期望的内容, {@code null} 表示期望空槽
     * @return 相同时返回 true
     */
    default boolean contentEquals(int slot, @Nullable ItemStack expected) {
        return ItemUtils.isContentEqual(this.read(slot), expected);
    }

    /**
     * 槽位自身的堆叠上限, 不含物品自带的堆叠上限.
     *
     * @param slot 存储槽位
     * @return 该槽位的堆叠上限
     */
    int maxStackSize(int slot);

    /**
     * 返回槽位的物理身份, 相同 {@link SlotKey} 表示最终写入同一位置.
     * <p><strong>归属在存储存活期间必须按值保持稳定</strong>. 拼接存储应返回最终分段的坐标;
     * 该坐标只用于判等, 读写仍使用当前存储的槽位.
     *
     * @param slot 存储槽位
     * @return 该槽位的 SlotKey
     */
    @NotNull
    default SlotKey keyOf(int slot) {
        return new SlotKey(this, slot);
    }

    /**
     * 检查内容所在的位置是否仍可访问.
     * <p>返回 {@code false} 后, ReferencingInventory 会在刷新时退役, 随后的读取为空且写入失败.
     *
     * @return 还在时返回 true
     */
    default boolean alive() {
        return true;
    }
}
