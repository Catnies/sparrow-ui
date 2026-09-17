package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

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

    // 规划那一刻的内容. <strong>只读</strong>, 还算不算数要问 isStale.
    public final @Nullable ItemStack @NotNull [] planned() {
        return this.planned;
    }

    // 需要进提交临界区就交出一把带序号的锁, 引擎按序号升序加锁. 返回 null 表示这种实现不靠锁串行.
    @Nullable
    protected abstract StateLock stateLock();

    // 手上这份基准还是不是当前内容. 中间被别的写操作顶掉了就算失效, 整笔事务按冲突处理.
    public abstract boolean isStale();

    // 按写集算出下一份完整状态, 先算完再统一交换.
    // <strong>必须在同一个临界区里、isStale 刚通过之后调用</strong>, 否则算出来的东西就是基于过期内容的.
    protected abstract @Nullable ItemStack @Nullable [] buildNextState(@NotNull List<SlotChange> deltas);

    // 把上一步算好的状态正式换上去. 到这一步为止都还能放弃, 换完就生效了.
    protected abstract void swapTo(@Nullable ItemStack @Nullable [] nextState);

    // 内部状态已经生效, 这一步把内容写进外部容器. 排在 Post 之前, 但已经离开临界区.
    protected abstract void land(@NotNull List<SlotChange> deltas);

    // 引擎拿到一堆锁之后按 order 升序加, 这个固定顺序就是跨 Inventory 事务不会死锁的全部依据.
    public record StateLock(@NotNull ReentrantLock lock, long order) {
    }
}
