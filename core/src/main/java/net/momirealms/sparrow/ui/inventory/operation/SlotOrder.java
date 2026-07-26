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
     * 以给定槽号序列建立顺序; 序列必须是 {@code [0, slots.length)} 的排列.
     *
     * @throws IllegalArgumentException 当存在越界或重复槽号时
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
     */
    public int slotAt(int index) {
        return this.slots[index];
    }
}
