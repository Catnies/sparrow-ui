package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.function.IntPredicate;

/**
 * 隐藏部分槽位的 ViewInventory: 可见槽按底层 Inventory 槽位顺序压缩成连续的当前 Inventory 槽位, 自己不持有槽数据.
 * <p>读写通过当前 Inventory 槽位与底层 Inventory 槽位的双向映射委托给底层; 批量操作只在可见槽上规划, 被遮住的槽
 * 既放不进去也收不出来.
 */
public final class ObscuredInventory extends ViewInventory {
    private final SparrowInventory underlying; // 被包装的底层 Inventory
    private final int[] visibleSlots; // 当前 Inventory 槽位 -> 底层 Inventory 槽位, 按底层槽位升序
    private final int[] logicalSlots; // 底层 Inventory 槽位 -> 当前 Inventory 槽位, 被遮槽为 -1

    /**
     * 以"被遮谓词"创建 ViewInventory: 谓词返回 {@code true} 的底层 Inventory 槽位会被隐藏.
     *
     * @param underlying 底层 Inventory
     * @param isObscured 判断某个底层槽是否被隐藏的谓词
     */
    public ObscuredInventory(@NotNull SparrowInventory underlying, @NotNull IntPredicate isObscured) {
        this.underlying = underlying;
        // 两遍扫描建立双向映射: 第一遍给每个底层 Inventory 槽位编号(被遮记为 -1), 第二遍反填出可见槽列表
        int underlyingSize = underlying.size();
        this.logicalSlots = new int[underlyingSize];
        int visible = 0;
        for (int slot = 0; slot < underlyingSize; slot++) {
            this.logicalSlots[slot] = isObscured.test(slot) ? -1 : visible++;
        }
        this.visibleSlots = new int[visible];
        for (int slot = 0; slot < underlyingSize; slot++) {
            if (this.logicalSlots[slot] != -1) {
                this.visibleSlots[this.logicalSlots[slot]] = slot;
            }
        }
    }

    @Override
    public int size() {
        return this.visibleSlots.length;
    }

    @Override
    @NotNull
    SlotKey.Anchor resolveSlot(int slot) {
        return this.underlying.resolveSlot(this.visibleSlots[slot]);
    }

    @Override
    void collectRoots(@NotNull LinkedHashSet<RootInventory> roots) {
        collectRootsFrom(this.underlying, roots);
    }

    /**
     * {@inheritDoc}
     *
     * <p>先读取底层 Inventory 的一次性内容副本, 再投影出可见槽, 因而所有可见槽来自同一次读取.
     */
    @Override
    public @Nullable ItemStack @NotNull [] snapshot() {
        @Nullable ItemStack[] full = this.underlying.snapshot();
        @Nullable ItemStack[] projected = new ItemStack[this.visibleSlots.length];
        for (int i = 0; i < this.visibleSlots.length; i++) {
            projected[i] = full[this.visibleSlots[i]];
        }
        return projected;
    }

    /**
     * {@inheritDoc}
     *
     * <p>为可见槽投影分配新数组, 但不复制底层返回的物品实例.
     */
    @Override
    public @Nullable ItemStack @NotNull [] unsafeSnapshot() {
        @Nullable ItemStack[] full = this.underlying.unsafeSnapshot();
        @Nullable ItemStack[] projected = new ItemStack[this.visibleSlots.length];
        for (int i = 0; i < this.visibleSlots.length; i++) {
            projected[i] = full[this.visibleSlots[i]];
        }
        return projected;
    }

    /**
     * {@inheritDoc}
     *
     * <p>把底层遍历顺序投影到当前 Inventory: 保持底层配置的访问偏好, 只过滤被遮槽.
     */
    @Override
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        SlotOrder underlyingOrder = this.underlying.iterationOrder(category);
        int[] projected = new int[this.visibleSlots.length];
        int position = 0;
        for (int i = 0; i < underlyingOrder.size(); i++) {
            int logical = this.logicalSlots[underlyingOrder.slotAt(i)];
            if (logical != -1) {
                projected[position++] = logical;
            }
        }
        return SlotOrder.of(projected);
    }

    /**
     * {@inheritDoc}
     *
     * <p>没设置就跟着底层走, 设置了就盖住底层设置.
     */
    @Override
    int fallbackGuiPriority(@NotNull OperationCategory category) {
        return this.underlying.guiPriority(category);
    }
}
