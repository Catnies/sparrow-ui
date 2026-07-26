package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.function.IntPredicate;

/**
 * 隐藏部分槽位的库存视图: 可见槽按底层槽序压缩为连续逻辑槽号, 自身不持有槽数据.
 * <p>读写经双射映射委托底层; 批量操作只在可见槽上规划, 被遮槽既不被放入也不被
 * 收取. 丢弃视图对底层零影响.
 * <p>本视图不是独立事件源: 订阅即对底层根库存的转发订阅, 观察到的是根库存的
 * 全部事务(含被遮槽的变更). 迭代顺序取底层顺序中可见槽的投影.
 * guiPriority 未显式设置的类别透传底层取值, 清除显式设置后恢复透传.
 */
public final class ObscuredInventory extends SparrowInventory {
    private final SparrowInventory underlying;
    private final int[] visibleSlots; // 逻辑槽 -> 底层槽, 按底层槽序升序
    private final int[] logicalSlots; // 底层槽 -> 逻辑槽, 被遮槽为 -1

    /**
     * 以"被遮谓词"创建视图: 谓词返回 {@code true} 的底层槽被隐藏.
     *
     * @throws IllegalArgumentException 当底层不是内建实现时
     */
    public ObscuredInventory(@NotNull Inventory underlying, @NotNull IntPredicate isObscured) {
        if (!(underlying instanceof SparrowInventory sparrowUnderlying)) {
            throw new IllegalArgumentException("obscured underlying must be a built-in inventory, got: " + underlying.getClass().getName());
        }
        this.underlying = sparrowUnderlying;

        int underlyingSize = sparrowUnderlying.size();
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
    Anchor resolveSlot(int slot) {
        return this.underlying.resolveSlot(this.visibleSlots[slot]);
    }

    @Override
    void collectRoots(@NotNull LinkedHashSet<AbstractInventory> roots) {
        this.underlying.collectRoots(roots);
    }

    @Override
    public @Nullable ItemStack @NotNull [] snapshot() {
        // 底层一次快照后投影, 可见槽之间共享同一时刻的视图
        @Nullable ItemStack[] full = this.underlying.snapshot();
        @Nullable ItemStack[] projected = new ItemStack[this.visibleSlots.length];
        for (int i = 0; i < this.visibleSlots.length; i++) {
            projected[i] = full[this.visibleSlots[i]];
        }
        return projected;
    }

    @Override
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        // 底层顺序中可见槽的投影: 保持底层配置的访问偏好, 只过滤被遮槽
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

    // 未显式设置时透传底层取值, 显式设置即遮盖; 三态存储与覆盖判定都在基类完成
    @Override
    int fallbackGuiPriority(@NotNull OperationCategory category) {
        return this.underlying.guiPriority(category);
    }
}
