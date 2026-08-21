package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

// 一次规划读到的 Inventory 内容, 同时决定这个 Inventory 怎么参与事务: 加锁, 校验, 构造新状态, 交换和落地
// 这五步都由它自己给出做法, 事务引擎照着调用, 不用管面前是哪一种 Inventory.
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

    // 规划期看到的内容, 只用来读(规划读取, 事件 before 采样); 依据还成不成立一律问 isStale, 别自己拿数组比对.
    final @Nullable ItemStack @NotNull [] planned() {
        return this.planned;
    }

    // 本基准怎么参与提交临界区: 要加锁的交出锁凭证, 不加锁的返回 null.
    @Nullable
    abstract StateLock stateLock();

    // 规划依据是不是已经作废. 提交临界区内的乐观校验和候选复核的 ROOT_STATE 检查都问这一处.
    abstract boolean isStale();

    // 在提交临界区内算出应用变更后的新状态, 不需要换状态的返回 null; 只允许紧接着刚通过的 isStale 调用.
    abstract @Nullable ItemStack @Nullable [] buildNextState(@NotNull List<SlotChange> deltas);

    // 在提交临界区内把上一步的产物设为当前状态, 产物为 null 时什么都不做.
    abstract void swapTo(@Nullable ItemStack @Nullable [] nextState);

    // 状态提交后, post 派发前的落地动作; 调不调由引擎按事务属性决定(External 同步就免了回写).
    abstract void land(@NotNull List<SlotChange> deltas);

    // 全序加锁凭证: 引擎按 order 升序逐把加锁, 跨 Inventory 的事务因此不会互相锁死.
    record StateLock(@NotNull ReentrantLock lock, long order) {
    }

    // 内容就在 Inventory 自己状态数组里时用的基准: planned 就是规划那一刻的状态数组本身, 同时兼任校验依据 ——
    // 元素发布后不再改动, 换内容就是换数组, 所以比一下引用就知道期间有没有别人提交过.
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
            // 内容就在状态数组里, 上一步换过数组就算落地了, 这里没别的事要做.
        }
    }

    // 内容放在外部存储里时用的基准: planned 是新建时逐槽读存储填出来的临时数组, 只用来读内容;
    // 校验改看新建时记下的 modCount —— 之后任何写入或吸收外部变更都会让它对不上.
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
