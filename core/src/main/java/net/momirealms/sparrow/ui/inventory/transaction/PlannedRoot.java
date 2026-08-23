package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

// 封装一种 Inventory 的规划快照与提交协议.
@ApiStatus.Internal
public abstract class PlannedRoot {
    private final SparrowInventory inventory;
    private final @Nullable ItemStack @NotNull [] planned;

    protected PlannedRoot(@NotNull SparrowInventory inventory, @Nullable ItemStack @NotNull [] planned) {
        this.inventory = inventory;
        this.planned = planned;
    }

    @NotNull
    public final SparrowInventory inventory() {
        return this.inventory;
    }

    // <strong>规划内容只读</strong>, 有效性由 isStale 判断.
    public final @Nullable ItemStack @NotNull [] planned() {
        return this.planned;
    }

    // 需要加入提交临界区时返回全序锁凭证.
    @Nullable
    protected abstract StateLock stateLock();

    // 判断规划依据是否已经失效.
    public abstract boolean isStale();

    // <strong>只可在同一临界区通过 isStale 后调用</strong>.
    protected abstract @Nullable ItemStack @Nullable [] buildNextState(@NotNull List<SlotChange> deltas);

    // 在提交临界区内发布上一步构造的状态.
    protected abstract void swapTo(@Nullable ItemStack @Nullable [] nextState);

    // 状态提交后, Post 派发前执行外部落地.
    protected abstract void land(@NotNull List<SlotChange> deltas);

    // 引擎按 order 升序加锁, 为跨 Inventory 事务提供固定锁序.
    public record StateLock(@NotNull ReentrantLock lock, long order) {
    }
}
