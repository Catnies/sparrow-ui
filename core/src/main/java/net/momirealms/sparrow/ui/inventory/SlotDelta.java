package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
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
        // 先克隆再归一化: 判空针对私有克隆, 入参的并发修改无法走私空表示进入 delta
        this.before = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(before));
        this.after = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(after));
    }

    // trusted 参数仅用于区分签名; 此构造器复用内部快照实例, 调用方必须保证实例归内部所有
    // TODO: 这块设计有点丑陋, 也许之后改成静态方法?
    private SlotDelta(int slot, @Nullable ItemStack before, @Nullable ItemStack after, boolean trusted) {
        this.slot = slot;
        this.before = before;
        this.after = after;
    }

    // 视图把逻辑槽 delta 映射到底层槽时使用: 只换槽号, 内部快照不再克隆
    @NotNull
    SlotDelta relocatedTo(int slot) {
        return new SlotDelta(slot, this.before, this.after, true);
    }

    public int slot() {
        return this.slot;
    }

    /**
     * 变更前的物品; 每次调用返回独立克隆, 空槽返回 {@code null}.
     */
    @Nullable
    public ItemStack before() {
        return ItemUtils.copyOrNull(this.before);
    }

    /**
     * 变更后的物品; 每次调用返回独立克隆, 清空槽位时返回 {@code null}.
     */
    @Nullable
    public ItemStack after() {
        return ItemUtils.copyOrNull(this.after);
    }

    // 提交路径直接把该内部实例写入新快照, 免去二次克隆; 内部代码不得变异它
    @Nullable
    ItemStack rawAfter() {
        return this.after;
    }
}
