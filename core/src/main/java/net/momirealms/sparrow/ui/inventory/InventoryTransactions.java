package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.RootInventoryChange;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Inventory 事务引擎: 所有 Inventory 写操作最终都汇到这里, 由它保证一笔事务要么全部生效, 要么全部不生效.
 * <p>一笔事务走四步: plan 由调用方先做完(在规划内容上算好每个槽改成什么) → pre 在不持锁的状态下
 * 询问观察者, 任何一个观察者都能取消整笔事务 → commit 在锁内核对规划基准状态引用是否仍然相同,
 * 核对通过才换上新内容 → post 释放锁后把事件按提交顺序派出去. 一笔事务涉及多个 RootInventory 时,
 * 按每个 Inventory 创建时领到的固定序号依次加锁, 即便多线程同时跑跨 Inventory 事务也不会死锁.
 * <p>事务形状的校验合并由 {@link TransactionValidator} 完成, Pre 阶段的候选值编辑由
 * {@link TransactionDraft} 保存, 事件的准备和派发由 {@link TransactionNotification} 承担.
 */
final class InventoryTransactions {

    private InventoryTransactions() {
    }

    /**
     * 提交一笔事务: 成功返回 {@link TransactionResult.Committed}, 其余结果都表示零变更.
     *
     * @param reason 变更原因
     * @param scopes 各 RootInventory 写集; ViewInventory 规划中同一 RootInventory 出现多次是合法的, 内部会合并
     * @param bypassPre 为 {@code true} 时跳过 pre 阶段的询问, 谁也取消不了这笔事务 (post 事件照常派发)
     * @return 事务结果; 只要不是 Committed, 所有参与 RootInventory 都保持原样
     * @throws IllegalArgumentException 当事务形状非法时(没有 RootInventory 写集, 某个写集没有变更, 槽号越界, 同一个槽被写两次)
     * @throws RuntimeException 当提交后处理失败时; 此时 Sparrow 内部状态已经提交, 异常不表示零变更
     * @throws Error 当提交后处理失败时; 此时 Sparrow 内部状态已经提交, 异常不表示零变更
     */
    @NotNull
    static TransactionResult commit(@NotNull UpdateReason reason, @NotNull List<TransactionScope> scopes, boolean bypassPre) {
        return commitDetailed(reason, scopes, bypassPre, Set.of()).result();
    }

    /**
     * 提交一笔事务, 并在取消时返回最终留下取消的 Inventory.
     *
     * @param reason 变更原因
     * @param scopes 各 RootInventory 写集
     * @param bypassPre 为 {@code true} 时跳过 pre 阶段
     * @param rejected 不允许最终草稿重新写入的物理槽位
     * @return 事务结果及取消来源; 非事件取消或没有取消时来源为 {@code null}
     */
    @NotNull
    static CommitAttempt commitDetailed(
            @NotNull UpdateReason reason,
            @NotNull List<TransactionScope> scopes,
            boolean bypassPre,
            @NotNull Set<SlotKey> rejected
    ) {
        List<TransactionScope> declared = TransactionValidator.validateAndMerge(scopes);
        TransactionDraft draft = new TransactionDraft(declared);

        // 在调用提交前处理器之前, 先记住本笔事务需要通知的所有订阅者.
        @Nullable SparrowInventory cancelledBy = null;
        List<TransactionNotification> updates = prepareUpdates(reason, declared, draft.rootChanges(), !bypassPre);

        // 按顺序派发 PreUpdateEvent, 每个 Inventory 处理后的取消状态会交给下一个 Inventory.
        if (!bypassPre) {
            for (int i = 0; i < updates.size(); i++) {
                cancelledBy = updates.get(i).publishPre(cancelledBy, draft);
            }
            if (cancelledBy != null) {
                return new CommitAttempt(TransactionResult.Cancelled.INSTANCE, cancelledBy);
            }
        }

        List<TransactionScope> declaredFinal = draft.scopes();
        if (!rejected.isEmpty()) {
            for (int i = 0; i < declaredFinal.size(); i++) {
                TransactionScope scope = declaredFinal.get(i);
                List<SlotChange> changes = scope.slotChanges();
                for (int j = 0; j < changes.size(); j++) {
                    if (rejected.contains(scope.inventory().physicalKey(changes.get(j).slot()))) {
                        return new CommitAttempt(TransactionResult.Cancelled.INSTANCE, null);
                    }
                }
            }
        }
        List<TransactionScope> ordered = sortByLockOrder(declaredFinal);
        List<RootInventoryChange> rootChanges = draft.rootChanges();
        for (int i = 0; i < updates.size(); i++) {
            updates.get(i).preparePost(rootChanges);
        }

        int locked = 0;
        try {
            // 按全序逐把加锁, 消除跨 RootInventory 事务的死锁可能
            for (; locked < ordered.size(); locked++) {
                ordered.get(locked).inventory().writeLock().lock();
            }

            // 乐观校验: 任一规划基准状态引用已变说明有并发提交插入, 整体放弃
            for (int i = 0; i < ordered.size(); i++) {
                TransactionScope scope = ordered.get(i);
                if (scope.inventory().currentState() != scope.planned()) {
                    return new CommitAttempt(TransactionResult.Conflicted.INSTANCE, null);
                }
            }

            // 先为全部 RootInventory 构造新的内部状态版本再统一交换, 保证意外异常发生时尚未改动任何状态.
            @Nullable ItemStack[][] newStates = new ItemStack[ordered.size()][];
            for (int i = 0; i < ordered.size(); i++) {
                newStates[i] = applyDeltas(ordered.get(i));
            }
            for (int i = 0; i < ordered.size(); i++) {
                ordered.get(i).inventory().swapState(newStates[i]);
            }

            // 更换内容时就把 PostUpdateEvent 放入队列, 防止后提交的事务先发出通知.
            for (int i = 0; i < updates.size(); i++) {
                updates.get(i).reservePost();
            }
        } finally {
            for (int i = locked - 1; i >= 0; i--) {
                ordered.get(i).inventory().writeLock().unlock();
            }
        }

        // 先让每个 RootInventory 完成提交后的工作, ReferencingInventory 会在这里把内容写回外部容器.
        // 因此提交后处理器运行时能够读到最新内容. 一个 RootInventory 失败也不能跳过其他 RootInventory, 最后再统一抛出异常.
        Throwable afterCommitFailure = null;
        try {
            for (int i = 0; i < ordered.size(); i++) {
                TransactionScope scope = ordered.get(i);
                afterCommitFailure = ThrowableUtils.captureUnchecked(
                        afterCommitFailure,
                        () -> scope.inventory().afterCommit(scope.slotChanges())
                );
            }
        } finally {
            // 所有 RootInventory 都处理完后, 当前提交后事件才可以按队列顺序发送.
            for (int i = 0; i < updates.size(); i++) {
                updates.get(i).markPostReady();
            }
            for (int i = 0; i < updates.size(); i++) {
                updates.get(i).drainPost();
            }
        }
        ThrowableUtils.throwIfUnchecked(afterCommitFailure);
        return new CommitAttempt(new TransactionResult.Committed(rootChanges), null);
    }

    /**
     * 按固定顺序排列参与事务的 Inventory, 避免并发事务互相等待.
     * <p>这个顺序只用于加锁和写入. 事件中的变化仍保持调用方传入的顺序.
     *
     * @param declared 按调用方传入顺序排列的修改内容
     * @return 按加锁顺序排列的新列表
     */
    @NotNull
    private static List<TransactionScope> sortByLockOrder(List<TransactionScope> declared) {
        List<TransactionScope> ordered = new ArrayList<>(declared);
        ordered.sort(Comparator.comparingLong(scope -> scope.inventory().lockOrder()));
        return ordered;
    }

    /**
     * 找出本笔事务需要通知的 Inventory, 并提前准备好各自的事件.
     * <p>Composite 可能同时使用多个被修改的 RootInventory, 但它仍然只处理一次.
     * PreUpdateEvent 接收者只按原始事务投影确定; PostUpdateEvent 接收者会先记录,
     * 等 PreUpdateEvent 完成后再按最终变更决定是否创建事件.
     *
     * @param reason 事务触发原因
     * @param scopes 本笔事务修改到的 RootInventory
     * @param rootChanges 整笔事务的 RootInventory 变更组
     * @param includePre 是否需要通知提交前订阅者
     * @return 本笔事务需要发送的 Inventory 更新事件
     */
    @NotNull
    private static List<TransactionNotification> prepareUpdates(
            @NotNull UpdateReason reason,
            @NotNull List<TransactionScope> scopes,
            @NotNull List<RootInventoryChange> rootChanges,
            boolean includePre
    ) {
        // 同一个 Inventory 可能登记在多个 RootInventory 中, 这里只保留一份.
        List<InventoryUpdateChannel> channels = new ArrayList<>();
        IdentityHashMap<InventoryUpdateChannel, Boolean> seen = new IdentityHashMap<>();
        for (int i = 0; i < scopes.size(); i++) {
            scopes.get(i).inventory().collectUpdateChannels(channels, seen);
        }

        // 记录当前订阅者名单, 并把 RootInventory 槽位变更投影成各 Inventory 自己的槽位变更.
        List<TransactionNotification> updates = new ArrayList<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            TransactionNotification update = channels.get(i).prepare(reason, rootChanges, includePre);
            if (update != null) {
                updates.add(update);
            }
        }
        return updates;
    }

    // 复制当前内部状态版本, 再应用槽位变更, 得到新的内部状态版本.
    private static @Nullable ItemStack @NotNull [] applyDeltas(TransactionScope scope) {
        @Nullable ItemStack[] next = scope.inventory().currentState().clone();
        List<SlotChange> deltas = scope.slotChanges();
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            next[delta.slot()] = delta.unsafeAfter();
        }
        return next;
    }

    record CommitAttempt(@NotNull TransactionResult result, @Nullable SparrowInventory cancelledBy) {
    }
}
