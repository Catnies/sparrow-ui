package net.momirealms.sparrow.ui.inventory.operation;

import org.jetbrains.annotations.NotNull;

/**
 * 批量算法访问槽位的迭代顺序, 不可变且构造即校验.
 * <p>顺序必须是 {@code [0, size)} 的一个排列: 覆盖全部槽且无重复.
 */
public final class SlotOrder {
    private final int[] slots; // slots[i] = 第 i 个被访问的槽号

    private SlotOrder(int[] slots) {
        this.slots = slots;
    }

    /**
     * 自然顺序: 0, 1, 2, ... size-1.
     *
     * @param size 槽位数量
     * @return 自然顺序
     * @throws IllegalArgumentException 当尺寸为负数时
     */
    @NotNull
    public static SlotOrder natural(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("order size must not be negative: " + size);
        }
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return new SlotOrder(slots);
    }

    /**
     * 以给定槽号序列建立顺序, 序列会被复制一份, 之后改动入参不影响这个顺序.
     *
     * @param slots 槽号序列, 必须是 {@code [0, slots.length)} 的一个排列
     * @return 按该序列访问槽位的顺序
     * @throws IllegalArgumentException 当槽号越界或出现重复时
     */
    @NotNull
    public static SlotOrder of(int... slots) {
        int[] copy = slots.clone();
        // 长度为 n 且每个值都在 [0, n) 内无重复, 即为排列, 无需再验覆盖性
        boolean[] seen = new boolean[copy.length];
        for (int i = 0; i < copy.length; i++) {
            int slot = copy[i];
            if (slot < 0 || slot >= copy.length) {
                throw new IllegalArgumentException("slot " + slot + " is out of range for an order of size " + copy.length);
            }
            if (seen[slot]) {
                throw new IllegalArgumentException("duplicate slot " + slot + " in iteration order");
            }
            seen[slot] = true;
        }
        return new SlotOrder(copy);
    }

    public int size() {
        return this.slots.length;
    }

    /**
     * 返回第 {@code index} 个被访问的槽号.
     *
     * @param index 访问次序, 从 0 开始
     * @return 该次序上的槽号
     * @throws IndexOutOfBoundsException 当次序越界时
     */
    public int slotAt(int index) {
        return this.slots[index];
    }

    /**
     * 返回访问次序与当前顺序完全相反的新顺序, 当前实例保持不变.
     *
     * @return 逆序的新顺序
     */
    @NotNull
    public SlotOrder reversed() {
        // 排列的逆序仍是排列, 直接走私有构造, 不再重复校验
        int[] reversed = new int[this.slots.length];
        for (int i = 0; i < reversed.length; i++) {
            reversed[i] = this.slots[reversed.length - 1 - i];
        }
        return new SlotOrder(reversed);
    }
}
