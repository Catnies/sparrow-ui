package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把多个 Inventory 按声明顺序拼接成一个逻辑 Inventory 的视图, 自己不持有槽数据.
 */
public final class CompositeInventory extends ViewInventory {
    private final SparrowInventory[] members;   // 按声明序排列的成员, 构造后固定
    private final int[] memberOffsets;          // memberOffsets[i] = 成员 i 的逻辑槽起点
    private final int size;                     // 全部成员槽位数之和, 即逻辑槽总数

    private volatile @Nullable SlotOrder addOrder;
    private volatile @Nullable SlotOrder collectOrder;
    private volatile @Nullable SlotOrder otherOrder;

    /**
     * 以声明顺序拼接给定的 Inventory.
     *
     * @param members 拼接成员, 至少一个
     * @throws IllegalArgumentException 当成员为空或展开后存在真实槽位重叠时
     */
    public CompositeInventory(@NotNull List<? extends SparrowInventory> members) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("composite inventory requires at least one member");
        }
        this.members = new SparrowInventory[members.size()];
        this.memberOffsets = new int[members.size()];
        int offset = 0;
        for (int i = 0; i < members.size(); i++) {
            SparrowInventory member = members.get(i);
            this.members[i] = member;
            this.memberOffsets[i] = offset;
            offset += member.size();
        }
        this.size = offset;
        this.requireNoOverlap();
    }

    /**
     * 把全部逻辑槽展开到最终真实槽位并拒绝重叠;
     * 两个镜像根指向同一个 Bukkit 槽同样算重叠.
     *
     * @throws IllegalArgumentException 当两个逻辑槽指向同一块真实存储时
     */
    private void requireNoOverlap() {
        HashSet<SlotKey> seen = new HashSet<>();
        for (int slot = 0; slot < this.size; slot++) {
            SlotKey key = this.physicalKey(slot);
            if (!seen.add(key)) {
                throw new IllegalArgumentException("composite members overlap at physical slot " + key);
            }
        }
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    @NotNull
    SlotKey.Anchor resolveSlot(int slot) {
        int index = this.memberIndexOf(slot);
        return this.members[index].resolveSlot(slot - this.memberOffsets[index]);
    }

    @Override
    void collectRoots(@NotNull LinkedHashSet<RootInventory> roots) {
        for (int i = 0; i < this.members.length; i++) {
            collectRootsFrom(this.members[i], roots);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>逐成员快照后拼接: 跨成员不承诺同一时刻.
     */
    @Override
    public @Nullable ItemStack @NotNull [] snapshot() {
        @Nullable ItemStack[] combined = new ItemStack[this.size];
        for (int i = 0; i < this.members.length; i++) {
            @Nullable ItemStack[] part = this.members[i].snapshot();
            System.arraycopy(part, 0, combined, this.memberOffsets[i], part.length);
        }
        return combined;
    }

    @NotNull
    @Override
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        SlotOrder explicit = switch (category) {
            case ADD -> this.addOrder;
            case COLLECT -> this.collectOrder;
            case OTHER -> this.otherOrder;
        };
        if (explicit != null) {
            return explicit;
        }

        int[] combined = new int[this.size];
        int position = 0;
        for (int i = 0; i < this.members.length; i++) {
            SlotOrder memberOrder = this.members[i].iterationOrder(category);
            for (int j = 0; j < memberOrder.size(); j++) {
                combined[position++] = this.memberOffsets[i] + memberOrder.slotAt(j);
            }
        }
        return SlotOrder.of(combined);
    }

    /**
     * 显式覆盖指定类别的逻辑槽遍历顺序.
     *
     * @param category 操作类别
     * @param order 遍历顺序, 尺寸必须等于拼接后的总槽数
     * @throws IllegalArgumentException 当顺序尺寸与拼接尺寸不符时
     */
    public void setIterationOrder(@NotNull OperationCategory category, @NotNull SlotOrder order) {
        if (order.size() != this.size) {
            throw new IllegalArgumentException("iteration order size " + order.size() + " does not match composite size " + this.size);
        }
        switch (category) {
            case ADD -> this.addOrder = order;
            case COLLECT -> this.collectOrder = order;
            case OTHER -> this.otherOrder = order;
        }
    }

    /**
     * 定位逻辑槽属于第几个成员;
     * 成员数量通常很小, 直接从后往前线性查找.
     *
     * @param slot 逻辑槽号
     * @return 成员下标
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    private int memberIndexOf(int slot) {
        if (slot >= 0) {
            for (int i = this.members.length - 1; i >= 0; i--) {
                if (slot >= this.memberOffsets[i]) {
                    if (slot < this.memberOffsets[i] + this.members[i].size()) {
                        return i;
                    }
                    break;
                }
            }
        }
        throw new IndexOutOfBoundsException("slot " + slot + " is out of bounds for composite size " + this.size);
    }
}
