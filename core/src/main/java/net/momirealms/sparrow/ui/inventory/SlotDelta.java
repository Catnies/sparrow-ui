package net.momirealms.sparrow.ui.inventory;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 单个槽位的一次变更: 槽号与变更前后的物品快照.
 * <p>构造时对入参归一化并克隆, 实例自持内部快照且不可变; 事件处理器长期持有
 * 本对象不构成风险. 读取 {@link #before()} 与 {@link #after()} 每次返回独立克隆.
 */
public final class SlotDelta {
    private final int slot;
    @Nullable
    private final ItemStack before; // 变更前的内部快照, 空槽为 null
    @Nullable
    private final ItemStack after; // 变更后的内部快照, 提交后与库存快照共享同一实例

    SlotDelta(int slot, @Nullable ItemStack before, @Nullable ItemStack after) {
        this.slot = slot;
        this.before = ItemStackValues.cloneOrNull(ItemStackValues.normalize(before));
        this.after = ItemStackValues.cloneOrNull(ItemStackValues.normalize(after));
    }

    public int slot() {
        return this.slot;
    }

    /**
     * 变更前的物品; 每次调用返回独立克隆, 空槽返回 {@code null}.
     */
    @Nullable
    public ItemStack before() {
        return ItemStackValues.cloneOrNull(this.before);
    }

    /**
     * 变更后的物品; 每次调用返回独立克隆, 清空槽位时返回 {@code null}.
     */
    @Nullable
    public ItemStack after() {
        return ItemStackValues.cloneOrNull(this.after);
    }

    // 提交路径直接把该内部实例写入新快照, 免去二次克隆; 内部代码不得变异它
    @Nullable
    ItemStack rawAfter() {
        return this.after;
    }
}
