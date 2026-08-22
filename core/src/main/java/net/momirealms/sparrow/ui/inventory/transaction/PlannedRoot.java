package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

// 一次规划读到的 Inventory 内容, 同时决定这个 Inventory 怎么参与事务: 加锁, 校验, 构造新状态, 交换和落地
// 这五步都由它自己给出做法, 事务引擎照着调用, 不用管面前是哪一种 Inventory.
// 实现由持有内容的那个 Inventory 自己给出, 状态数组和 modCount 这些命门因此不必离开各自的类.
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

    // 规划期看到的内容, 只用来读(规划读取, 事件 before 采样); 依据还成不成立一律问 isStale, 别自己拿数组比对.
    public final @Nullable ItemStack @NotNull [] planned() {
        return this.planned;
    }

    // 本基准怎么参与提交临界区: 要加锁的交出锁凭证, 不加锁的返回 null.
    @Nullable
    protected abstract StateLock stateLock();

    // 规划依据是不是已经作废. 提交临界区内的乐观校验和候选复核的 ROOT_STATE 检查都问这一处.
    public abstract boolean isStale();

    // 在提交临界区内算出应用变更后的新状态, 不需要换状态的返回 null; 只允许紧接着刚通过的 isStale 调用.
    protected abstract @Nullable ItemStack @Nullable [] buildNextState(@NotNull List<SlotChange> deltas);

    // 在提交临界区内把上一步的产物设为当前状态, 产物为 null 时什么都不做.
    protected abstract void swapTo(@Nullable ItemStack @Nullable [] nextState);

    // 状态提交后, post 派发前的落地动作; 调不调由引擎按事务属性决定(External 同步就免了回写).
    protected abstract void land(@NotNull List<SlotChange> deltas);

    // 全序加锁凭证: 引擎按 order 升序逐把加锁, 跨 Inventory 的事务因此不会互相锁死.
    public record StateLock(@NotNull ReentrantLock lock, long order) {
    }
}
