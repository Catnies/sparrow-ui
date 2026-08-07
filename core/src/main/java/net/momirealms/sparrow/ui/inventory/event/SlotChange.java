package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SlotChange {
    private final int slot;                   // 承载这条记录的 Inventory 坐标
    @Nullable private final ItemStack before; // 变更前的内部物品副本, 空槽为 null
    @Nullable private final ItemStack after;  // 变更后的内部物品副本, 空槽为 null

    @ApiStatus.Internal
    public SlotChange(int slot, @Nullable ItemStack before, @Nullable ItemStack after) {
        this(slot, before, after, true);
    }

    private SlotChange(int slot, @Nullable ItemStack before, @Nullable ItemStack after, boolean copy) {
        this.slot = slot;
        this.before = ItemUtils.nullIfEmpty(copy ? ItemUtils.copyOrNull(before) : before);
        this.after = ItemUtils.nullIfEmpty(copy ? ItemUtils.copyOrNull(after) : after);
    }

    /**
     * 采纳调用方给出的变更后物品实例, 不复制, 让物品在搬运过程中保持对象身份以对齐原版.
     * <p><strong>只给 Sparrow 内部规划器使用.</strong> {@code after} 必须满足三者之一: 由本次规划新造,
     * 取自某个 Inventory 的内部状态数组, 或由菜单交出所有权的光标实例(整堆搬运, 数量与组件都不变).
     * 采纳之后本记录与 Inventory 状态共享同一实例, 因此任何一方都不得修改它 —— 数量或组件要变就造新对象.
     * <p>{@code before} 只用于记账, 不参与身份对齐, 因此照常复制: 它若与提交后仍在流转的实例共享引用,
     * 事件历史与净变化统计会随该实例之后的变化而漂移, 同一笔事务对不同订阅者给出不同结果.
     * <p>面向外部的写入口一律走复制构造器: 事件处理器写进来的物品必须与内部状态隔离,
     * 否则处理器直接改动物品组件就会绕过事务, 并在并发事务之间互相污染.
     *
     * @param slot 承载本记录的 Inventory 坐标
     * @param before 变更前的物品, 空槽为 {@code null}; 会被复制
     * @param after 变更后的物品, 清空槽位为 {@code null}; 会被原样持有
     * @return 直接持有给定变更后实例的槽位变更
     */
    @ApiStatus.Internal
    @NotNull
    public static SlotChange adopt(int slot, @Nullable ItemStack before, @Nullable ItemStack after) {
        return new SlotChange(slot, ItemUtils.copyOrNull(before), after, false);
    }

    public int slot() {
        return this.slot;
    }

    /**
     * 判断本次变更是否有物品流入槽位.
     * <p>本方法与 {@link #isRemove()} 不互斥. 两个不相似的非空物品发生替换时,
     * 槽位同时存在物品流入与流出, 两个方法都会返回 {@code true}.
     *
     * @return {@link #addedAmount()} 大于 {@code 0} 时返回 {@code true}
     */
    public boolean isAdd() {
        return this.addedAmount() > 0;
    }

    /**
     * 判断本槽位是否只有物品流入, 没有物品流出.
     * <p>不相似物品发生替换时同时存在两个方向的物品流, 本方法返回 {@code false}.
     *
     * @return {@link #isAdd()} 为 {@code true} 且 {@link #isRemove()} 为 {@code false} 时返回 {@code true}
     */
    public boolean isAddOnly() {
        return this.isAdd() && !this.isRemove();
    }

    /**
     * 判断本次变更是否有物品流出槽位.
     * <p>本方法与 {@link #isAdd()} 不互斥. 两个不相似的非空物品发生替换时,
     * 槽位同时存在物品流入与流出, 两个方法都会返回 {@code true}.
     *
     * @return {@link #removedAmount()} 大于 {@code 0} 时返回 {@code true}
     */
    public boolean isRemove() {
        return this.removedAmount() > 0;
    }

    /**
     * 判断本槽位是否只有物品流出, 没有物品流入.
     * <p>不相似物品发生替换时同时存在两个方向的物品流, 本方法返回 {@code false}.
     *
     * @return {@link #isRemove()} 为 {@code true} 且 {@link #isAdd()} 为 {@code false} 时返回 {@code true}
     */
    public boolean isRemoveOnly() {
        return this.isRemove() && !this.isAdd();
    }

    /**
     * 判断两个非空物品是否发生了不相似的替换.
     * <p>替换同时包含旧物品流出和新物品流入, 因此本方法返回 {@code true} 时,
     * {@link #isAdd()} 与 {@link #isRemove()} 也会返回 {@code true}.
     * 物品相似性按 Bukkit {@link ItemStack#isSimilar(ItemStack)} 定义:
     * 材质, 名称, 附魔或其他物品元数据不同都可能使本方法返回 {@code true}.
     *
     * @return 两个非空物品是否发生了不相似的替换
     */
    public boolean isReplacement() {
        return this.after != null && this.before != null && !this.after.isSimilar(this.before);
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
        if (this.before == null || !this.after.isSimilar(this.before)) {
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
        if (this.after == null || !this.before.isSimilar(this.after)) {
            return this.before.getAmount();
        }
        return Math.max(this.before.getAmount() - this.after.getAmount(), 0);
    }

    /**
     * 变更前的物品; 每次调用返回独立副本, 空槽返回 {@code null}.
     */
    @Nullable
    public ItemStack before() {
        return ItemUtils.copyOrNull(this.before);
    }

    /**
     * 变更后的物品; 每次调用返回独立副本, 清空槽位时返回 {@code null}.
     */
    @Nullable
    public ItemStack after() {
        return ItemUtils.copyOrNull(this.after);
    }

    /**
     * 零拷贝地返回变更前的物品, 原本为空槽时返回 {@code null}.
     * <p>返回值属于事务记录内部. 调用方只能在当前调用栈内读取, 不得修改或保存引用;
     * 违反约定会污染事件历史, 净变化计算以及后续读取结果.
     *
     * @return 变更前的内部物品引用, 原本为空槽时为 {@code null}
     */
    @Nullable
    public ItemStack unsafeBefore() {
        return this.before;
    }

    /**
     * 零拷贝地返回变更后的物品, 清空槽位时返回 {@code null}.
     * <p>该实例可能在提交后成为 Inventory 的实际槽位内容. 调用方只能在当前调用栈内读取,
     * 不得修改或保存引用; 违反约定会绕过事务, 事件, Window 刷新和外部容器同步,
     * 并污染事件历史与净变化计算.
     *
     * @return 变更后的内部物品引用, 清空槽位时为 {@code null}
     */
    @Nullable
    public ItemStack unsafeAfter() {
        return this.after;
    }
}
