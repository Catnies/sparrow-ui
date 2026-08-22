package net.momirealms.sparrow.ui.inventory.storage;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ReferencingInventory 的内容实际存放的地方, 读写一律以它为准: Bukkit 容器只是它的一种实现.
 * <p>槽位坐标是存储自己的坐标(Bukkit 实现即 Bukkit 容器槽位).
 * <p>同一 Inventory 的所有访问必须串行, 串行由调用方负责, 框架不提供并发保障.
 */
@ApiStatus.Experimental
public interface ExternalStorage {

    /**
     * 直接封装一个 NMS 容器.
     * <p>槽位数量、堆叠上限与读写都取自这个容器自己.
     * 存活判断顺着容器问回它所属的方块实体或实体, 问不出来的一律当作还可用.
     *
     * @param container {@code net.minecraft.world.Container} 实例
     * @return 该容器的外部存储
     */
    @NotNull
    static ExternalStorage ofContainer(@NotNull Object container) {
        return new FixedContainerStorage(container);
    }

    /**
     * 存储的槽位数量, 构造后不得变化.
     *
     * @return 槽位数量
     */
    int size();

    /**
     * 读取槽位现值.
     * <p>可以返回内部活实例, 也可以返回副本: 引擎只读取不修改, 也不把它持有到本次操作之外.
     * 需要与引擎彻底隔离的实现返回副本即可.
     *
     * @param slot 存储槽位
     * @return 槽位现值, 空槽返回 {@code null}
     */
    @Nullable
    ItemStack read(int slot);

    /**
     * 批量读取全部槽位现值, 供逐槽对比与规划基准构建.
     * <p>返回数组归调用方本次使用, 元素契约与 {@link #read(int)} 相同.
     * 默认实现逐槽 {@code read}; 有更廉价批量通道的实现(如 Bukkit 的 {@code getContents})应当覆写.
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
     * <p>传入实例是引擎为本次调用准备的独立对象, 所有权归存储, 可直接持有, 不必再复制.
     *
     * @param slot 存储槽位
     * @param item 新内容, 清空槽位为 {@code null}
     */
    void write(int slot, @Nullable ItemStack item);

    /**
     * 槽位自身的堆叠上限, 不含物品自带的堆叠上限.
     *
     * @param slot 存储槽位
     * @return 该槽位的堆叠上限
     */
    int maxStackSize(int slot);

    /**
     * 把存储槽位换算成 {@link SlotKey}, 两个 Inventory 落到同一个 SlotKey 时判定为同一个存储位置.
     * <p>归属要按值判等并在存储活着的期间保持稳定, 不要交出每次取用都新建的包装对象.
     * 拼起来的存储要把归属下放到真正存放这一格的那一层, 并把槽号换算到那一层的坐标里.
     *
     * @param slot 存储槽位
     * @return 该槽位的 SlotKey
     */
    @NotNull
    default SlotKey keyOf(int slot) {
        return new SlotKey(this, slot);
    }

    /**
     * 检查和内容存放的地方是不是还在.
     * <p>ReferencingInventory 每次刷新都会执行, 若回答 {@code false},
     * Inventory 则就地删除: 之后读到的是空, 写入一律失败, 快速转移与双击收集也不再把它当成目标.
     *
     * @return 还在时返回 true
     */
    default boolean alive() {
        return true;
    }
}
