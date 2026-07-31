package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.function.IntPredicate;

/**
 * 隐藏部分槽位的 Inventory 视图: 可见槽按底层槽顺序压缩成连续的逻辑槽号, 自己不持有槽数据.
 * <p>读写通过逻辑槽与底层槽的双向映射委托给底层; 批量操作只在可见槽上规划, 被遮住的槽
 * 既放不进去也收不出来. 丢弃视图对底层没有任何影响.
 * <p>本视图不是独立事件源: 订阅等于转发订阅底层的根 Inventory, 能观察到根 Inventory 的
 * 全部事务(包括被遮槽的变更). 遍历顺序取底层顺序中可见槽的部分.
 */
public final class ObscuredInventory extends SparrowInventory {
    private final SparrowInventory underlying; // 被装饰的底层 Inventory
    private final int[] visibleSlots; // 逻辑槽 -> 底层槽, 按底层槽序升序
    private final int[] logicalSlots; // 底层槽 -> 逻辑槽, 被遮槽为 -1

    /**
     * 以"被遮谓词"创建视图: 谓词返回 {@code true} 的底层槽会被隐藏.
     *
     * @param underlying 底层 Inventory
     * @param isObscured 判断某个底层槽是否被隐藏的谓词
     */
    public ObscuredInventory(@NotNull SparrowInventory underlying, @NotNull IntPredicate isObscured) {
        this.underlying = underlying;
        // 两遍扫描建立双向映射: 第一遍给每个底层槽编逻辑号(被遮记为 -1), 第二遍反填出可见槽列表
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

    /**
     * {@inheritDoc}
     *
     * <p>只数可见槽.
     */
    @Override
    public int size() {
        return this.visibleSlots.length;
    }

    /**
     * {@inheritDoc}
     *
     * <p>先查出逻辑槽对应的底层槽, 再委托底层换算.
     */
    @Override
    @NotNull
    SlotKey.Anchor resolveSlot(int slot) {
        return this.underlying.resolveSlot(this.visibleSlots[slot]);
    }

    /**
     * {@inheritDoc}
     *
     * <p>根全部来自底层.
     */
    @Override
    void collectRoots(@NotNull LinkedHashSet<RootInventory> roots) {
        this.underlying.collectRoots(roots);
    }

    /**
     * {@inheritDoc}
     *
     * <p>先拿底层的一次性快照再投影出可见槽, 可见槽之间共享同一时刻的视图.
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
     * <p>取底层顺序中可见槽的投影: 保持底层配置的访问偏好, 只过滤被遮槽.
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
     * <p>透传底层取值, 得到"没设置就跟着底层走, 设置了就盖住底层"的效果;
     * 三态存储与覆盖判定都在基类完成.
     */
    @Override
    int fallbackGuiPriority(@NotNull OperationCategory category) {
        return this.underlying.guiPriority(category);
    }
}
