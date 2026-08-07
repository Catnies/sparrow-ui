package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Inventory 事务引擎: 所有 Inventory 写操作最终都汇到这里, 由它保证一笔事务要么全部生效, 要么全部不生效.
 * <p>一笔事务走四步: plan 由调用方先做完(在规划内容上算好每个槽改成什么) → pre 在不持锁的状态下
 * 询问观察者, 任何一个观察者都能取消整笔事务 → commit 在临界区内核对每条规划基准仍然有效
 * (失效语义由基准的家族决定), 核对通过才落定新内容 → post 释放锁后把事件按提交顺序派出去.
 * 一笔事务涉及多个 Inventory 时, 按加锁凭证的固定序号依次加锁, 即便多线程同时跑跨 Inventory
 * 事务也不会死锁; 不参与加锁的家族靠串行访问契约保证安全.
 * <p>事务形状的校验和 Pre 阶段的候选值编辑都由 {@link TransactionDraft} 保存,
 * 事件的准备和派发由 {@link TransactionNotification} 承担.
 */
final class InventoryTransactions {

    private InventoryTransactions() {
    }

    /**
     * 提交一笔事务: 成功返回 {@link TransactionResult.Committed}, 其余结果都表示零变更.
     *
     * @param reason 变更原因
     * @param scopes 各 Inventory 写集; 同一个 Inventory 至多出现一次
     * @param bypassPre 为 {@code true} 时跳过 pre 阶段的询问, 谁也取消不了这笔事务 (post 事件照常派发)
     * @return 事务结果; 只要不是 Committed, 所有参与 Inventory 都保持原样
     * @throws IllegalArgumentException 当事务形状非法时(没有 Inventory 写集, 某个写集没有变更, 槽号越界, 同一个槽被写两次, 同一个 Inventory 出现两次)
     * @throws RuntimeException 当提交后处理失败时; 此时 Sparrow 内部状态已经提交, 异常不表示零变更
     * @throws Error 当提交后处理失败时; 此时 Sparrow 内部状态已经提交, 异常不表示零变更
     */
    @NotNull
    static TransactionResult commit(@NotNull UpdateReason reason, @NotNull List<TransactionScope> scopes, boolean bypassPre) {
        return commit(reason, new TransactionDraft(scopes), null, bypassPre, null, List.of(), () -> true);
    }

    /**
     * 提交一笔草稿已经在事务外准备好的事务.
     * <p>玩家交互走这条入口: 写集草稿在第一道闸门之前就要建好, 让 Bukkit 事件, Sparrow 事件和 Pre 处理器
     * 依次写进同一份草稿, 上一个监听器的结果留给下一个.
     * <p>最终提交条件在 Pre 完成后、取得 Inventory 写锁前检查; 返回 false 时按冲突处理,
     * 整笔事务保持零变更. 条件运行在锁外, 可以安全复核玩家与 Window 状态.
     * <p>读集只在锁内做基准状态引用的乐观校验, 本方法不会刷新它.
     * 调用方必须在调用前自行完成刷新: 事务中段调用 {@link SparrowInventory#prepareWrite()} 会让
     * ReferencingInventory 提交一笔嵌套的 External 事务并派发它自己的 Post, 相当于在本笔事务的
     * Pre 与 commit 之间重入事件系统.
     * <p>因此 Pre 处理器直接写外部 Bukkit 容器的内容不会被本笔事务发现, 会被提交后的回写覆盖.
     * 这与写集的既有行为一致 —— 写集同样只在 Pre 之前刷新一次.
     *
     * @param reason 变更原因
     * @param draft 已经校验过形状的写集草稿
     * @param interaction 触发本笔事务的交互副作用草稿, 非玩家交互传 {@code null}
     * @param bypassPre 为 {@code true} 时跳过 pre 阶段的询问
     * @param committedCallback 状态提交后, Post 派发前执行的收尾动作
     * @param readSet 只做乐观校验的额外读集
     * @param commitGuard 取得写锁前的最终提交条件
     * @return 事务结果; 只要不是 Committed, 所有参与 Inventory 都保持原样
     */
    @NotNull
    static TransactionResult commit(
            @NotNull UpdateReason reason,
            @NotNull TransactionDraft draft,
            @Nullable InteractionDraft interaction,
            boolean bypassPre,
            @Nullable Runnable committedCallback,
            @NotNull List<SparrowInventory.PlannedRoot> readSet,
            @NotNull BooleanSupplier commitGuard
    ) {
        return commit(reason, draft, interaction, bypassPre, committedCallback, readSet, commitGuard, true);
    }

    /**
     * 提交一笔内容取自外部容器的同步事务, 供 ReferencingInventory 把外部世界的既成变更吸收进 Bukkit 内容镜像.
     * <p>这类事务的目标内容就是刚从外部容器读到的内容, 因此跳过 pre 阶段, 也跳过提交后的外部容器回写:
     * 回写只会用等值副本换掉容器里的物品实例, 白白作废外部持有的引用.
     *
     * @param scope 本笔同步事务的写集
     * @return 事务结果; 只要不是 Committed, 参与的 Inventory 就保持原样
     */
    @NotNull
    static TransactionResult commitExternalSync(@NotNull TransactionScope scope) {
        return commit(UpdateReason.External.INSTANCE, new TransactionDraft(List.of(scope)), null, true, null, List.of(), () -> true, false);
    }

    /**
     * 事务提交的统一实现.
     *
     * @param writeBack 为 {@code true} 时在状态提交后调用各 Inventory 的提交后处理, 把内容写回外部容器
     */
    @NotNull
    private static TransactionResult commit(
            @NotNull UpdateReason reason,
            @NotNull TransactionDraft draft,
            @Nullable InteractionDraft interaction,
            boolean bypassPre,
            @Nullable Runnable committedCallback,
            @NotNull List<SparrowInventory.PlannedRoot> readSet,
            @NotNull BooleanSupplier commitGuard,
            boolean writeBack
    ) {
        // Inventory 级冻结兜底: 玩家侧写入在规划层就该被拒, 这里拦住漏网的玩家事务, 不发 Pre 也零变更.
        if (reason instanceof PlayerUpdateReason) {
            List<TransactionScope> frozenCheck = draft.scopes();
            for (int i = 0; i < frozenCheck.size(); i++) {
                if (frozenCheck.get(i).inventory().frozen()) {
                    return TransactionResult.Cancelled.INSTANCE;
                }
            }
        }

        // 在调用提交前处理器之前, 先记住本笔事务需要通知的所有订阅者.
        boolean cancelled = false;
        List<TransactionScope> declared = draft.scopes();
        List<TransactionNotification> updates = prepareUpdates(reason, declared, !bypassPre);

        // 按顺序派发 PreUpdateEvent, 每个 Inventory 处理后的取消状态会交给下一个 Inventory.
        if (!bypassPre) {
            for (int i = 0; i < updates.size(); i++) {
                cancelled = updates.get(i).publishPre(cancelled, draft, interaction);
            }
            if (cancelled) {
                return TransactionResult.Cancelled.INSTANCE;
            }
        }

        if (!commitGuard.getAsBoolean()) {
            return TransactionResult.Conflicted.INSTANCE;
        }
        // 最终条件已经通过, 两份草稿同时封笔: 之后的加锁写入必须看到定案的内容.
        if (interaction != null) {
            interaction.seal();
        }

        List<TransactionScope> declaredFinal = draft.scopes();
        List<SparrowInventory.PlannedRoot.StateLock> locks = collectLocks(declaredFinal, readSet);

        // Pre 期间新纳入的 Inventory 没有参加本轮 Pre, 但它们的订阅者照样要收到 Post.
        // 已参与的 Inventory 只可追加, 不可移除也不可换位, 新参与者就是写集末尾多出来的那一段.
        List<TransactionScope> included = declaredFinal.subList(declared.size(), declaredFinal.size());
        if (!included.isEmpty()) {
            updates.addAll(prepareUpdates(reason, included, false));
        }
        for (int i = 0; i < updates.size(); i++) {
            updates.get(i).preparePost(declaredFinal);
        }

        int locked = 0;
        try {
            // 按全序逐把加锁, 消除跨 Inventory 事务的死锁可能; 不参与加锁的家族没有凭证, 不在列表里.
            for (; locked < locks.size(); locked++) {
                locks.get(locked).lock().lock();
            }

            // 乐观校验: 任一规划基准已失效说明有并发提交插入, 整体放弃; 失效语义由基准的家族决定.
            for (int i = 0; i < declaredFinal.size(); i++) {
                if (declaredFinal.get(i).basis().isStale()) {
                    return TransactionResult.Conflicted.INSTANCE;
                }
            }
            for (int i = 0; i < readSet.size(); i++) {
                if (readSet.get(i).isStale()) {
                    return TransactionResult.Conflicted.INSTANCE;
                }
            }

            // 先为全部写集构造提交产物再统一交换, 保证意外异常发生时尚未改动任何状态;
            // 不参与统一交换的家族产物为 null, 交换时无事发生.
            @Nullable ItemStack[][] staged = new ItemStack[declaredFinal.size()][];
            for (int i = 0; i < declaredFinal.size(); i++) {
                TransactionScope scope = declaredFinal.get(i);
                staged[i] = scope.basis().buildNextState(scope.slotChanges());
            }
            for (int i = 0; i < declaredFinal.size(); i++) {
                declaredFinal.get(i).basis().swapTo(staged[i]);
            }

            // 更换内容时就把 PostUpdateEvent 放入队列, 防止后提交的事务先发出通知.
            for (int i = 0; i < updates.size(); i++) {
                updates.get(i).reservePost();
            }
        } finally {
            for (int i = locked - 1; i >= 0; i--) {
                locks.get(i).lock().unlock();
            }
        }

        // 先让每个 Inventory 完成提交后的工作, ReferencingInventory 会在这里把内容写回外部容器.
        // 因此提交后处理器运行时能够读到最新内容. 一个 Inventory 失败也不能跳过其他 Inventory, 最后再统一抛出异常.
        Throwable afterCommitFailure = null;
        try {
            if (writeBack) {
                for (int i = 0; i < declaredFinal.size(); i++) {
                    TransactionScope scope = declaredFinal.get(i);
                    afterCommitFailure = ThrowableUtils.captureUnchecked(
                            afterCommitFailure,
                            () -> scope.basis().land(scope.slotChanges())
                    );
                }
            }
            if (committedCallback != null) {
                afterCommitFailure = ThrowableUtils.captureUnchecked(afterCommitFailure, committedCallback);
            }
        } finally {
            // 所有 Inventory 都处理完后, 当前提交后事件才可以按队列顺序发送.
            for (int i = 0; i < updates.size(); i++) {
                updates.get(i).markPostReady();
            }
            for (int i = 0; i < updates.size(); i++) {
                updates.get(i).drainPost();
            }
        }
        ThrowableUtils.throwIfUnchecked(afterCommitFailure);
        return new TransactionResult.Committed(draft.rootChanges());
    }

    /**
     * 按固定顺序排出本笔事务需要取得的锁凭证, 避免并发事务互相等待.
     * <p>这个顺序只用于加锁. 写入, 落地与事件中的变化都保持调用方传入的顺序.
     * 不参与加锁的家族没有凭证, 在此被自然滤除.
     *
     * @param writes 按调用方传入顺序排列的写集
     * @param reads 只做乐观校验的额外读集
     * @return 去重并按锁序号排列的锁凭证
     */
    @NotNull
    private static List<SparrowInventory.PlannedRoot.StateLock> collectLocks(List<TransactionScope> writes, List<SparrowInventory.PlannedRoot> reads) {
        List<SparrowInventory.PlannedRoot.StateLock> locks = new ArrayList<>(writes.size() + reads.size());
        IdentityHashMap<SparrowInventory, Boolean> seen = new IdentityHashMap<>();
        for (int i = 0; i < writes.size(); i++) {
            collectLock(writes.get(i).basis(), seen, locks);
        }
        for (int i = 0; i < reads.size(); i++) {
            collectLock(reads.get(i), seen, locks);
        }
        locks.sort(Comparator.comparingLong(SparrowInventory.PlannedRoot.StateLock::order));
        return locks;
    }

    // 同一个 Inventory 在写集与读集中可能各出现一次, 只取第一份凭证.
    private static void collectLock(
            SparrowInventory.PlannedRoot root,
            IdentityHashMap<SparrowInventory, Boolean> seen,
            List<SparrowInventory.PlannedRoot.StateLock> locks
    ) {
        if (seen.put(root.inventory(), Boolean.TRUE) != null) return;
        @Nullable SparrowInventory.PlannedRoot.StateLock lock = root.stateLock();
        if (lock != null) {
            locks.add(lock);
        }
    }

    /**
     * 找出本笔事务需要通知的 Inventory, 并提前准备好各自的事件.
     * <p>每个 Inventory 至多拥有一个订阅器, 写集里同一个 Inventory 也至多出现一次, 因此它只处理一次.
     * PreUpdateEvent 接收者只按原始事务的变更确定; PostUpdateEvent 接收者会先记录,
     * 等 PreUpdateEvent 完成后再按最终变更决定是否创建事件.
     *
     * @param reason 事务触发原因
     * @param scopes 本轮需要准备通知的写集
     * @param includePre 是否需要通知提交前订阅者
     * @return 本轮需要新发送的 Inventory 更新事件
     */
    @NotNull
    private static List<TransactionNotification> prepareUpdates(
            @NotNull UpdateReason reason,
            @NotNull List<TransactionScope> scopes,
            boolean includePre
    ) {
        List<TransactionNotification> updates = new ArrayList<>(scopes.size());
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            InventoryUpdateChannel channel = scope.inventory().updateChannelIfPresent();
            if (channel == null) continue;
            TransactionNotification update = channel.prepare(reason, scope, includePre);
            if (update != null) {
                updates.add(update);
            }
        }
        return updates;
    }

}
