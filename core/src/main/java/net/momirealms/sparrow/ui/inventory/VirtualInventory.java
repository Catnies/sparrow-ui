package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.exception.InventoryDecodeException;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.UUID;

/**
 * 自己持有槽位数据的 Inventory 实现.
 * <p>身份用 {@link UUID} 表达, 也是持久化的主键, 槽内容在构造时快照化;
 * 尺寸构造后固定, 不支持 resize.
 * <p>堆叠上限, 遍历顺序与 guiPriority 属于配置: 改配置不产生槽变更, 不走事务也不派发事件,
 * 只影响之后的批量规划; 改配置与进行中的事务交错时, 读到新旧值都属正常(弱一致).
 */
public final class VirtualInventory extends RootInventory {
    private final UUID uuid; // 持久化身份, 序列化时作为主键
    private volatile int @Nullable [] slotMaxStackSizes;    // 逐槽堆叠上限, null 表示全部用默认值; 数组不可变, 改就整组替换
    private volatile @Nullable SlotOrder addOrder;          // ADD 类别的遍历顺序, null 回退自然顺序
    private volatile @Nullable SlotOrder collectOrder;
    private volatile @Nullable SlotOrder otherOrder;

    /**
     * 以随机 UUID 创建全空 Inventory.
     *
     * @param size 槽位数量
     */
    public VirtualInventory(int size) {
        this(UUID.randomUUID(), size);
    }

    /**
     * 以给定 UUID 创建全空 Inventory.
     *
     * @param uuid 持久化身份
     * @param size 槽位数量
     * @throws IllegalArgumentException 当尺寸为负数时
     */
    public VirtualInventory(@NotNull UUID uuid, int size) {
        super(new ItemStack[requireNonNegativeSize(size)]);
        this.uuid = uuid;
    }

    /**
     * 以随机 UUID 创建 Inventory, 初始内容取给定数组快照.
     *
     * @param initial 初始槽位内容, 空槽位置为 {@code null}
     */
    public VirtualInventory(@Nullable ItemStack @NotNull [] initial) {
        this(UUID.randomUUID(), initial);
    }

    /**
     * 以给定 UUID 创建 Inventory, 初始内容取给定数组快照.
     *
     * @param uuid 持久化身份
     * @param initial 初始槽位内容, 空槽位置为 {@code null}
     */
    public VirtualInventory(@NotNull UUID uuid, @Nullable ItemStack @NotNull [] initial) {
        super(initial);
        this.uuid = uuid;
    }

    /**
     * 从字节数组反序列化出 Inventory.
     * <p>只恢复 UUID 与槽内容, 堆叠上限等配置不随数据回来;
     * 必须在 SparrowUI 完成 setup 后调用.
     *
     * @param bytes 序列化数据
     * @return 还原出的 Inventory
     * @throws InventoryDecodeException 当输入不是本实现支持的完整数据时
     */
    @NotNull
    public static VirtualInventory deserialize(byte @NotNull [] bytes) {
        return VirtualInventoryCodec.deserialize(bytes);
    }

    /**
     * 把 UUID 与当前这一刻的槽内容序列化成字节数组.
     * <p>必须在 SparrowUI 完成 setup 后调用.
     *
     * @return 序列化数据
     */
    public byte @NotNull [] serialize() {
        return VirtualInventoryCodec.serialize(this);
    }

    /**
     * 返回本 Inventory 的持久化身份.
     *
     * @return 持久化用的 UUID
     */
    @NotNull
    public UUID uuid() {
        return this.uuid;
    }

    /**
     * {@inheritDoc}
     *
     * <p>逐槽配置过的上限优先生效, 没配置的槽回退默认值.
     */
    @Override
    public int slotMaxStackSize(int slot) {
        int[] maxes = this.slotMaxStackSizes;
        if (maxes != null) {
            return maxes[slot];
        }
        return super.slotMaxStackSize(slot);
    }

    /**
     * 设置单个槽位的堆叠上限.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param max 堆叠上限, 至少为 1
     * @throws IllegalArgumentException 当上限小于 1 时
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public synchronized void setMaxStackSize(int slot, int max) {
        if (max < 1)
            throw new IllegalArgumentException("max stack size must be at least 1: " + max);
        // 沿用快照惯例: 数组不可变, 改一个槽也是复制整组后换引用, 读者无锁安全.
        // synchronized 用来串行化配置写者: 复制-修改-写回不是原子操作, 两个线程同时改
        // 会基于同一份旧数组互相覆盖, 配置悄悄丢失
        int[] current = this.slotMaxStackSizes;
        int[] next = current != null ? current.clone() : filledWithDefault(this.size());
        next[slot] = max;
        this.slotMaxStackSizes = next;
    }

    /**
     * 整组设置每个槽位的堆叠上限.
     *
     * @param maxes 每个槽位的堆叠上限, 长度必须等于槽位数量
     * @throws IllegalArgumentException 当数组长度与槽位数量不符或任一上限小于 1 时
     */
    public synchronized void setMaxStackSizes(int @NotNull [] maxes) {
        if (maxes.length != this.size()) {
            throw new IllegalArgumentException("max stack size array length " + maxes.length + " does not match inventory size " + this.size());
        }
        int[] copy = maxes.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 1)
                throw new IllegalArgumentException("max stack size must be at least 1: " + copy[i]);
        }
        this.slotMaxStackSizes = copy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>显式设置过的类别返回设置值, 没设置的回退自然顺序.
     */
    @Override
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        SlotOrder order = switch (category) {
            case ADD -> this.addOrder;
            case COLLECT -> this.collectOrder;
            case OTHER -> this.otherOrder;
        };
        return order != null ? order : super.iterationOrder(category);
    }

    /**
     * 设置指定类别的批量操作按什么顺序遍历槽位.
     *
     * @param category 操作类别
     * @param order 遍历顺序, 尺寸必须等于槽位数量
     * @throws IllegalArgumentException 当顺序尺寸与槽位数量不符时
     */
    public void setIterationOrder(@NotNull OperationCategory category, @NotNull SlotOrder order) {
        if (order.size() != this.size()) {
            throw new IllegalArgumentException("iteration order size " + order.size() + " does not match inventory size " + this.size());
        }
        switch (category) {
            case ADD -> this.addOrder = order;
            case COLLECT -> this.collectOrder = order;
            case OTHER -> this.otherOrder = order;
        }
    }

    /**
     * 校验尺寸不为负数.
     *
     * @param size 槽位数量
     * @return 原样返回的尺寸
     * @throws IllegalArgumentException 当尺寸为负数时
     */
    private static int requireNonNegativeSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("inventory size must not be negative: " + size);
        }
        return size;
    }

    /**
     * 创建填满默认堆叠上限的数组.
     *
     * @param size 槽位数量
     * @return 填满 {@link #DEFAULT_MAX_STACK_SIZE} 的数组
     */
    private static int[] filledWithDefault(int size) {
        int[] maxes = new int[size];
        Arrays.fill(maxes, DEFAULT_MAX_STACK_SIZE);
        return maxes;
    }
}
