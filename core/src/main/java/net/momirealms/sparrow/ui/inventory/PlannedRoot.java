package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一次规划读到的 Inventory 内容, 同时决定这个 Inventory 怎么参与事务: 加锁, 校验, 构造新状态,
 * 交换和落地这五步都由它自己给出做法, 事务引擎照着调用, 不用管面前是哪一种 Inventory.
 * <p>{@code planned} 数组只用来读内容(规划读取, 事件 before 采样);
 * 规划依据还成不成立要问 {@link #isStale()} —— 两种实现的判断方式不一样, 调用方不要自己拿数组比对.
 */
abstract sealed class PlannedRoot {
    private final SparrowInventory inventory;
    private final @Nullable ItemStack @NotNull [] planned;

    PlannedRoot(@NotNull SparrowInventory inventory, @Nullable ItemStack @NotNull [] planned) {
        this.inventory = inventory;
        this.planned = planned;
    }

    @NotNull
    final SparrowInventory inventory() {
        return this.inventory;
    }

    final @Nullable ItemStack @NotNull [] planned() {
        return this.planned;
    }

    /**
     * 本基准参与提交临界区的方式: 需要加锁的返回锁凭证, 不加锁的返回 {@code null}.
     */
    @Nullable
    abstract StateLock stateLock();

    /**
     * 本基准是否已经失效. 提交临界区内的乐观校验与候选复核的 ROOT_STATE 检查共用本方法.
     */
    abstract boolean isStale();

    /**
     * 在提交临界区内构造应用变更后的新状态; 不需要交换状态的返回 {@code null}.
     * 只允许在 {@link #isStale()} 刚刚通过的同一临界区内调用.
     */
    abstract @Nullable ItemStack @Nullable [] buildNextState(@NotNull List<SlotChange> deltas);

    /**
     * 在提交临界区内把构造产物设为当前状态; 产物为 {@code null} 时无事发生.
     */
    abstract void swapTo(@Nullable ItemStack @Nullable [] nextState);

    /**
     * 状态提交后, post 事件派发前的落地动作. 是否调用由引擎按事务属性决定(External 同步免回写).
     *
     * @param deltas 本写集的槽位变更
     */
    abstract void land(@NotNull List<SlotChange> deltas);

    /**
     * 全序加锁凭证: 事务引擎按 {@code order} 升序逐把加锁, 消除跨 Inventory 事务的死锁可能.
     */
    record StateLock(@NotNull ReentrantLock lock, long order) {
    }

    /**
     * 内容就在 Inventory 自己状态数组里时用的规划基准: planned 就是规划那一刻的状态数组本身,
     * 它同时也是并发校验的依据 —— 数组元素发布后不再修改, 换内容就是换数组, 比引用就能发现并发提交.
     */
    static final class Stm extends PlannedRoot {

        Stm(@NotNull SparrowInventory inventory, @Nullable ItemStack @NotNull [] planned) {
            super(inventory, planned);
        }

        @Override
        @NotNull
        StateLock stateLock() {
            return new StateLock(this.inventory().writeLock(), this.inventory().lockOrder());
        }

        @Override
        boolean isStale() {
            return this.inventory().currentState() != this.planned();
        }

        @Override
        @Nullable ItemStack @NotNull [] buildNextState(@NotNull List<SlotChange> deltas) {
            // isStale 刚在同一临界区内通过, planned 与当前状态是同一个数组, 克隆它即克隆当前状态.
            @Nullable ItemStack[] next = this.planned().clone();
            for (int i = 0; i < deltas.size(); i++) {
                SlotChange delta = deltas.get(i);
                // 等值跳过: 内容没变就保留原元素, 让"实例换了"严格等价于"内容变了".
                @Nullable ItemStack after = delta.unsafeAfter();
                @Nullable ItemStack current = next[delta.slot()];
                next[delta.slot()] = ItemUtils.isContentEqual(current, after) ? current : after;
            }
            return next;
        }

        @Override
        void swapTo(@Nullable ItemStack @Nullable [] nextState) {
            if (nextState != null) {
                this.inventory().swapState(nextState);
            }
        }

        @Override
        void land(@NotNull List<SlotChange> deltas) {
            // 内容就在状态数组里, 上一步换过数组就已经落地了, 这里没有别的事情要做.
        }
    }

    /**
     * 内容放在外部存储里时用的规划基准: planned 是新建时逐槽读存储填出来的临时数组, 每次规划都重新建一份,
     * 只用来读内容; 并发校验改看新建时记下的 modCount —— 之后任何写入或吸收外部变更都会让它对不上.
     */
    static final class Live extends PlannedRoot {
        private final ReferencingInventory owner;
        private final long modCountAtPlan;

        Live(@NotNull ReferencingInventory owner, @Nullable ItemStack @NotNull [] planned, long modCountAtPlan) {
            super(owner, planned);
            this.owner = owner;
            this.modCountAtPlan = modCountAtPlan;
        }

        @Override
        @Nullable
        StateLock stateLock() {
            return null;
        }

        @Override
        boolean isStale() {
            // 已退役的 Inventory 没有任何基准还成立, 事务一律以 Conflicted 收场
            return this.owner.retired() || this.owner.liveModCount() != this.modCountAtPlan;
        }

        @Override
        @Nullable ItemStack @Nullable [] buildNextState(@NotNull List<SlotChange> deltas) {
            return null;
        }

        @Override
        void swapTo(@Nullable ItemStack @Nullable [] nextState) {
        }

        @Override
        void land(@NotNull List<SlotChange> deltas) {
            this.owner.liveApply(deltas);
        }
    }
}
