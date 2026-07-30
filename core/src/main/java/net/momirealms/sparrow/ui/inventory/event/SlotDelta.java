package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
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
    private final ItemStack after; // 变更后的内部快照, 提交后与快照共享同一实例

    @ApiStatus.Internal
    public SlotDelta(int slot, @Nullable ItemStack before, @Nullable ItemStack after) {
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

    /**
     * 视图把逻辑槽 delta 映射到底层槽时使用: 只换槽号, 内部快照不再克隆.
     */
    @NotNull
    @ApiStatus.Internal
    public SlotDelta relocatedTo(int slot) {
        return new SlotDelta(slot, this.before, this.after, true);
    }

    public int slot() {
        return this.slot;
    }

    /**
     * 判断本次变更是否向槽位中加入了物品.
     * <p>空槽变为非空槽, 或相似物品的数量增加时返回 {@code true}.
     * 两个不相似的非空物品之间的替换由 {@link #isSwap()} 表达.
     *
     * @return 本次变更是否加入了物品
     */
    public boolean isAdd() {
        if (this.after != null && this.before != null && this.after.isSimilar(this.before)) {
            return this.after.getAmount() > this.before.getAmount();
        }
        return this.before == null && this.after != null;
    }

    /**
     * 判断本次变更是否从槽位中移除了物品.
     * <p>非空槽变为空槽, 或相似物品的数量减少时返回 {@code true}.
     * 两个不相似的非空物品之间的替换由 {@link #isSwap()} 表达.
     *
     * @return 本次变更是否移除了物品
     */
    public boolean isRemove() {
        if (this.after != null && this.before != null && this.after.isSimilar(this.before)) {
            return this.after.getAmount() < this.before.getAmount();
        }
        return this.after == null && this.before != null;
    }

    /**
     * 判断两个非空物品是否发生了不相似的替换.
     * <p>这里的“交换”按 Bukkit {@link ItemStack#isSimilar(ItemStack)} 定义:
     * 材质、名称、附魔或其他物品元数据不同都可能使本方法返回 {@code true}.
     *
     * @return 两个非空物品是否发生了不相似的替换
     */
    public boolean isSwap() {
        return this.after != null && this.before != null && !this.after.isSimilar(this.before);
    }

    /**
     * 返回本次加入的物品数量.
     *
     * @return 加入的物品数量
     * @throws IllegalStateException 当 {@link #isAdd()} 为 {@code false} 时
     */
    public int addedAmount() {
        if (!this.isAdd()) {
            throw new IllegalStateException("No items have been added");
        }
        return this.before == null
                ? this.after.getAmount()
                : this.after.getAmount() - this.before.getAmount();
    }

    /**
     * 返回本次移除的物品数量.
     *
     * @return 移除的物品数量
     * @throws IllegalStateException 当 {@link #isRemove()} 为 {@code false} 时
     */
    public int removedAmount() {
        if (!this.isRemove()) {
            throw new IllegalStateException("No items have been removed");
        }
        return this.after == null
                ? this.before.getAmount()
                : this.before.getAmount() - this.after.getAmount();
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

    /**
     * 提交路径直接把该内部实例写入新快照, 免去二次克隆;
     * 内部代码不会修改它.
     */
    @Nullable
    @ApiStatus.Internal
    public ItemStack rawAfter() {
        return this.after;
    }
}
