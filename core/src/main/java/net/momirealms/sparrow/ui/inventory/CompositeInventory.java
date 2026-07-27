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
 * 把多个库存按声明顺序拼接为一个逻辑库存的视图, 自身不持有任何槽数据.
 * <p>跨子库存的批量操作是单个事务: 按根库存的锁序号全序加锁, 全成全败.
 * 单槽操作直接委托给命中的子库存. 组成在构造后固定; 展开到根库存槽后
 * 不允许任何重叠 —— 同一物理槽出现在两个逻辑槽会让写入语义无法自洽.
 * <p>本视图不是独立事件源: 订阅即对全部根库存的转发订阅(见基类契约).
 * 迭代顺序缺省为"各子库存自身顺序按声明序拼接", 可显式覆盖为逻辑槽域的顺序.
 */
public final class CompositeInventory extends SparrowInventory {
    private final SparrowInventory[] members; // 声明序成员, 构造后固定
    private final int[] memberOffsets; // memberOffsets[i] = 成员 i 的逻辑槽起点
    private final int size;

    private volatile @Nullable SlotOrder addOrder; // 显式覆盖的逻辑槽顺序, null 回退子拼接
    private volatile @Nullable SlotOrder collectOrder;
    private volatile @Nullable SlotOrder otherOrder;

    /**
     * 以声明顺序拼接给定库存.
     *
     * @throws IllegalArgumentException 当成员为空, 含非内建实现, 或展开后存在物理槽重叠时
     */
    public CompositeInventory(@NotNull List<? extends Inventory> members) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("composite inventory requires at least one member");
        }
        this.members = new SparrowInventory[members.size()];
        this.memberOffsets = new int[members.size()];
        int offset = 0;
        for (int i = 0; i < members.size(); i++) {
            Inventory member = members.get(i);
            // 事务原子性依赖内建实现的锁与快照协议, 第三方 Inventory 实现无法参与
            if (!(member instanceof SparrowInventory sparrowMember)) {
                throw new IllegalArgumentException("composite members must be built-in inventories, got: " + member.getClass().getName());
            }
            this.members[i] = sparrowMember;
            this.memberOffsets[i] = offset;
            offset += sparrowMember.size();
        }
        this.size = offset;
        this.requireNoOverlap();
    }

    // 展开全部逻辑槽到最终物理存储槽并拒绝重叠;不同镜像根指向同一 Bukkit 槽同样属于重叠.
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
    Anchor resolveSlot(int slot) {
        int index = this.memberIndexOf(slot);
        return this.members[index].resolveSlot(slot - this.memberOffsets[index]);
    }

    @Override
    void collectRoots(@NotNull LinkedHashSet<AbstractInventory> roots) {
        for (int i = 0; i < this.members.length; i++) {
            this.members[i].collectRoots(roots);
        }
    }

    @Override
    public @Nullable ItemStack @NotNull [] snapshot() {
        // 逐成员快照拼接: 每个成员内部一致, 跨成员不承诺同一时刻
        @Nullable ItemStack[] combined = new ItemStack[this.size];
        for (int i = 0; i < this.members.length; i++) {
            @Nullable ItemStack[] part = this.members[i].snapshot();
            System.arraycopy(part, 0, combined, this.memberOffsets[i], part.length);
        }
        return combined;
    }

    @Override
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        SlotOrder explicit = switch (category) {
            case ADD -> this.addOrder;
            case COLLECT -> this.collectOrder;
            case OTHER -> this.otherOrder;
        };
        if (explicit != null) {
            return explicit;
        }

        // 缺省顺序: 各成员自身顺序映射到逻辑槽域后按声明序拼接
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
     * 显式覆盖指定操作类别的逻辑槽迭代顺序.
     *
     * @throws IllegalArgumentException 当顺序尺寸与拼接尺寸不符时
     */
    public void iterationOrder(@NotNull OperationCategory category, @NotNull SlotOrder order) {
        if (order.size() != this.size) {
            throw new IllegalArgumentException("iteration order size " + order.size() + " does not match composite size " + this.size);
        }
        switch (category) {
            case ADD -> this.addOrder = order;
            case COLLECT -> this.collectOrder = order;
            case OTHER -> this.otherOrder = order;
        }
    }

    // 逻辑槽所属的成员下标; 成员数通常很小, 从后向前线性定位即可
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
