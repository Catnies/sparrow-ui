package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 一个逻辑槽位在事务前后的内容记录.
 * <p>普通访问器返回副本, {@code unsafe} 访问器暴露事务内部的只读对象.
 */
public final class SlotChange {
    private final int slot;                   // 承载这条记录的 Inventory 坐标
    @Nullable private final ItemStack before; // 变更前的内部物品副本, 空槽为 null
    @Nullable private final ItemStack after;  // 变更后的内部物品副本, 空槽为 null

    @ApiStatus.Internal
    public SlotChange(int slot, @Nullable ItemStack before, @Nullable ItemStack after) {
        this.slot = slot;
        this.before = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(before));
        this.after = ItemUtils.nullIfEmpty(ItemUtils.copyOrNull(after));
    }

    public int slot() {
        return this.slot;
    }

    /**
     * 判断本次变更是否有物品流入槽位.
     * <p>不相似物品发生替换时, {@link #isRemove()} 也返回 {@code true}.
     *
     * @return {@link #addedAmount()} 大于 {@code 0} 时返回 {@code true}
     */
    public boolean isAdd() {
        return this.addedAmount() > 0;
    }

    /**
     * 判断本槽位是否只有物品流入.
     *
     * @return {@link #isAdd()} 为 {@code true} 且 {@link #isRemove()} 为 {@code false} 时返回 {@code true}
     */
    public boolean isAddOnly() {
        return this.isAdd() && !this.isRemove();
    }

    /**
     * 判断本次变更是否有物品流出槽位.
     * <p>不相似物品发生替换时, {@link #isAdd()} 也返回 {@code true}.
     *
     * @return {@link #removedAmount()} 大于 {@code 0} 时返回 {@code true}
     */
    public boolean isRemove() {
        return this.removedAmount() > 0;
    }

    /**
     * 判断本槽位是否只有物品流出.
     *
     * @return {@link #isRemove()} 为 {@code true} 且 {@link #isAdd()} 为 {@code false} 时返回 {@code true}
     */
    public boolean isRemoveOnly() {
        return this.isRemove() && !this.isAdd();
    }

    /**
     * 判断两个非空物品是否发生了不相似的替换.
     * <p>替换同时计为旧物品流出和新物品流入.
     *
     * @return 两个非空物品是否发生了不相似的替换
     */
    public boolean isReplacement() {
        return this.after != null && this.before != null && !ItemUtils.isSimilar(this.after, this.before);
    }

    /**
     * 判断显式写入前后的槽位内容是否没有变化.
     * <p>本方法只描述槽位内容; 即使返回 {@code true}, 包含本记录的事务仍可能完成提交和事件派发.
     *
     * @return 两个方向都没有物品流时返回 {@code true}
     */
    public boolean isUnchanged() {
        return !this.isAdd() && !this.isRemove();
    }

    /**
     * 返回本次流入槽位的物品总量.
     * <p>没有物品流入时返回 {@code 0}; 不相似物品发生替换时返回变更后物品的完整数量.
     *
     * @return 流入数量, 不会小于 {@code 0}
     */
    public int addedAmount() {
        if (this.after == null) {
            return 0;
        }
        if (!ItemUtils.isSimilar(this.after, this.before)) {
            return this.after.getAmount();
        }
        return Math.max(this.after.getAmount() - this.before.getAmount(), 0);
    }

    /**
     * 返回本次流出槽位的物品总量.
     * <p>没有物品流出时返回 {@code 0}; 不相似物品发生替换时返回变更前物品的完整数量.
     *
     * @return 流出数量, 不会小于 {@code 0}
     */
    public int removedAmount() {
        if (this.before == null) {
            return 0;
        }
        if (!ItemUtils.isSimilar(this.before, this.after)) {
            return this.before.getAmount();
        }
        return Math.max(this.before.getAmount() - this.after.getAmount(), 0);
    }

    /**
     * 变更前的物品, 每次调用返回独立副本.
     *
     * @return 变更前物品的副本, 原本为空槽时为 {@code null}
     */
    @Nullable
    public ItemStack before() {
        return ItemUtils.copyOrNull(this.before);
    }

    /**
     * 变更后的物品, 每次调用返回独立副本.
     *
     * @return 变更后物品的副本, 清空槽位时为 {@code null}
     */
    @Nullable
    public ItemStack after() {
        return ItemUtils.copyOrNull(this.after);
    }

    /**
     * 零拷贝地返回变更前的物品, 原本为空槽时返回 {@code null}.
     * <p><strong>返回值只限当前调用栈读取, 不得修改或持有</strong>.
     *
     * @return 变更前的内部物品引用, 原本为空槽时为 {@code null}
     */
    @Nullable
    public ItemStack unsafeBefore() {
        return this.before;
    }

    /**
     * 零拷贝地返回变更后的物品, 清空槽位时返回 {@code null}.
     * <p>该实例可能成为提交后的实际槽位内容. <strong>返回值只限当前调用栈读取, 不得修改或持有</strong>.
     *
     * @return 变更后的内部物品引用, 清空槽位时为 {@code null}
     */
    @Nullable
    public ItemStack unsafeAfter() {
        return this.after;
    }
}
