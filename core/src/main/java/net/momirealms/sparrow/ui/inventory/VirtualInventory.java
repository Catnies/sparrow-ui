package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.UUID;

/**
 * 唯一自持槽数据的库存实现.
 * <p>身份由 {@link UUID} 表达, 是持久化的主键, 与并发锁序无关. 槽内容在构造时
 * 快照化; 尺寸构造后固定, 不提供 resize.
 * <p>堆叠上限, 迭代顺序与 guiPriority 是配置性状态: 修改不产生槽变更, 不走事务
 * 也不派发事件, 只影响之后的规划; 与进行中事务的交错以弱一致语义为准.
 */
public final class VirtualInventory extends AbstractInventory {
    private final UUID uuid;

    private volatile int @Nullable [] slotMaxStackSizes; // 每槽上限, null 表示全部使用默认值; 数组不可变, 修改即整组替换
    private volatile @Nullable SlotOrder addOrder; // null 回退自然顺序
    private volatile @Nullable SlotOrder collectOrder;
    private volatile @Nullable SlotOrder otherOrder;

    /**
     * 以随机 UUID 创建全空库存.
     */
    public VirtualInventory(int size) {
        this(UUID.randomUUID(), size);
    }

    /**
     * 以给定 UUID 创建全空库存.
     *
     * @throws IllegalArgumentException 当尺寸为负数时
     */
    public VirtualInventory(@NotNull UUID uuid, int size) {
        super(new ItemStack[requireNonNegativeSize(size)]);
        this.uuid = uuid;
    }

    /**
     * 以随机 UUID 创建库存, 初始内容取给定数组的归一化克隆.
     */
    public VirtualInventory(@Nullable ItemStack @NotNull [] initial) {
        this(UUID.randomUUID(), initial);
    }

    /**
     * 以给定 UUID 创建库存, 初始内容取给定数组的归一化克隆.
     */
    public VirtualInventory(@NotNull UUID uuid, @Nullable ItemStack @NotNull [] initial) {
        super(initial);
        this.uuid = uuid;
    }

    /**
     * 本库存的持久化身份.
     */
    @NotNull
    public UUID uuid() {
        return this.uuid;
    }

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
     * @throws IllegalArgumentException 当上限小于 1 时
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public synchronized void slotMaxStackSize(int slot, int max) {
        requirePositiveMax(max);
        // 沿用快照惯例: 数组不可变, 单槽修改即复制整组后替换引用, 读者无锁安全.
        // synchronized 串行化配置写者: 复制-修改-写回不是原子操作, 并发单槽修改
        // 会基于同一份旧数组互相覆盖, 造成配置静默丢失
        int[] current = this.slotMaxStackSizes;
        int[] next = current != null ? current.clone() : filledWithDefault(this.size());
        next[slot] = max;
        this.slotMaxStackSizes = next;
    }

    /**
     * 整组设置每槽堆叠上限.
     *
     * @throws IllegalArgumentException 当数组长度与库存尺寸不符或任一上限小于 1 时
     */
    public synchronized void slotMaxStackSizes(int @NotNull [] maxes) {
        if (maxes.length != this.size()) {
            throw new IllegalArgumentException("max stack size array length " + maxes.length + " does not match inventory size " + this.size());
        }
        int[] copy = maxes.clone();
        for (int i = 0; i < copy.length; i++) {
            requirePositiveMax(copy[i]);
        }
        this.slotMaxStackSizes = copy;
    }

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
     * 设置指定操作类别的迭代顺序.
     *
     * @throws IllegalArgumentException 当顺序尺寸与库存尺寸不符时
     */
    public void iterationOrder(@NotNull OperationCategory category, @NotNull SlotOrder order) {
        if (order.size() != this.size()) {
            throw new IllegalArgumentException("iteration order size " + order.size() + " does not match inventory size " + this.size());
        }
        switch (category) {
            case ADD -> this.addOrder = order;
            case COLLECT -> this.collectOrder = order;
            case OTHER -> this.otherOrder = order;
        }
    }

    private static int requireNonNegativeSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("inventory size must not be negative: " + size);
        }
        return size;
    }

    private static void requirePositiveMax(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max stack size must be at least 1: " + max);
        }
    }

    private static int[] filledWithDefault(int size) {
        int[] maxes = new int[size];
        Arrays.fill(maxes, DEFAULT_MAX_STACK_SIZE);
        return maxes;
    }
}
