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
import java.util.function.BooleanSupplier;

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
        return commit(reason, new TransactionDraft(scopes), null, bypassPre, null, List.of(), () -> true);
    }

    /**
     * 提交一笔草稿已经在事务外准备好的事务.
     * <p>玩家交互走这条入口: 写集草稿在第一道闸门之前就要建好, 让 Bukkit 事件, Sparrow 事件和 Pre 处理器
     * 依次写进同一份草稿, 上一个监听器的结果留给下一个.
     * <p>最终提交条件在 Pre 完成后、取得 RootInventory 写锁前检查; 返回 false 时按冲突处理,
     * 整笔事务保持零变更. 条件运行在锁外, 可以安全复核玩家与 Window 状态.
     * <p>读集只在锁内做基准状态引用的乐观校验, 本方法不会刷新它.
     * 调用方必须在调用前自行完成刷新: 事务中段调用 {@link RootInventory#prepareWrite()} 会让
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
     * @return 事务结果; 只要不是 Committed, 所有参与 RootInventory 都保持原样
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
        // 在调用提交前处理器之前, 先记住本笔事务需要通知的所有订阅者.
        boolean cancelled = false;
        List<TransactionScope> declared = draft.scopes();
        // 去重表跨两轮共用: Pre 期间新纳入 RootInventory 时, 同时观察新旧根的 Composite 不能被通知两次.
        IdentityHashMap<InventoryUpdateChannel, Boolean> notified = new IdentityHashMap<>();
        List<TransactionNotification> updates = prepareUpdates(reason, declared, draft.rootChanges(), !bypassPre, notified);

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
        List<RootInventory> lockOrder = lockOrder(declaredFinal, readSet);
        List<RootInventoryChange> rootChanges = draft.rootChanges();

        // Pre 期间新纳入的 RootInventory 没有参加本轮 Pre, 但只观察它们的 Inventory 照样要收到 Post.
        // 这与"Pre 新增的可见槽位不会漏掉提交后通知"是同一条裁决, 只是把新增槽位换成了新增根.
        // 已参与的 RootInventory 只可追加, 不可移除也不可换位, 新参与者就是写集末尾多出来的那一段.
        List<TransactionScope> included = declaredFinal.subList(declared.size(), declaredFinal.size());
        if (!included.isEmpty()) {
            updates.addAll(prepareUpdates(reason, included, rootChanges, false, notified));
        }
        for (int i = 0; i < updates.size(); i++) {
            updates.get(i).preparePost(rootChanges);
        }

        int locked = 0;
        try {
            // 按全序逐把加锁, 消除跨 RootInventory 事务的死锁可能
            for (; locked < lockOrder.size(); locked++) {
                lockOrder.get(locked).writeLock().lock();
            }

            // 乐观校验: 任一规划基准状态引用已变说明有并发提交插入, 整体放弃
            for (int i = 0; i < declaredFinal.size(); i++) {
                TransactionScope scope = declaredFinal.get(i);
                if (scope.inventory().currentState() != scope.planned()) {
                    return TransactionResult.Conflicted.INSTANCE;
                }
            }
            for (int i = 0; i < readSet.size(); i++) {
                SparrowInventory.PlannedRoot root = readSet.get(i);
                if (root.inventory().currentState() != root.planned()) {
                    return TransactionResult.Conflicted.INSTANCE;
                }
            }

            // 先为全部 RootInventory 构造新的内部状态版本再统一交换, 保证意外异常发生时尚未改动任何状态.
            @Nullable ItemStack[][] newStates = new ItemStack[declaredFinal.size()][];
            for (int i = 0; i < declaredFinal.size(); i++) {
                newStates[i] = applyDeltas(declaredFinal.get(i));
            }
            for (int i = 0; i < declaredFinal.size(); i++) {
                declaredFinal.get(i).inventory().swapState(newStates[i]);
            }

            // 更换内容时就把 PostUpdateEvent 放入队列, 防止后提交的事务先发出通知.
            for (int i = 0; i < updates.size(); i++) {
                updates.get(i).reservePost();
            }
        } finally {
            for (int i = locked - 1; i >= 0; i--) {
                lockOrder.get(i).writeLock().unlock();
            }
        }

        // 先让每个 RootInventory 完成提交后的工作, ReferencingInventory 会在这里把内容写回外部容器.
        // 因此提交后处理器运行时能够读到最新内容. 一个 RootInventory 失败也不能跳过其他 RootInventory, 最后再统一抛出异常.
        Throwable afterCommitFailure = null;
        try {
            for (int i = 0; i < declaredFinal.size(); i++) {
                TransactionScope scope = declaredFinal.get(i);
                afterCommitFailure = ThrowableUtils.captureUnchecked(
                        afterCommitFailure,
                        () -> scope.inventory().afterCommit(scope.slotChanges())
                );
            }
            if (committedCallback != null) {
                afterCommitFailure = ThrowableUtils.captureUnchecked(afterCommitFailure, committedCallback);
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
        return new TransactionResult.Committed(rootChanges);
    }

    /**
     * 按固定顺序排出本笔事务需要取得的写锁, 避免并发事务互相等待.
     * <p>这个顺序只用于加锁. 写入, 提交后处理与事件中的变化都保持调用方传入的顺序.
     *
     * @param writes 按调用方传入顺序排列的写集
     * @param reads 只做乐观校验的额外读集
     * @return 去重并按锁序号排列的 RootInventory
     */
    @NotNull
    private static List<RootInventory> lockOrder(
            List<TransactionScope> writes,
            List<SparrowInventory.PlannedRoot> reads
    ) {
        List<RootInventory> inventories = new ArrayList<>(writes.size() + reads.size());
        IdentityHashMap<RootInventory, Boolean> seen = new IdentityHashMap<>();
        for (int i = 0; i < writes.size(); i++) {
            RootInventory inventory = writes.get(i).inventory();
            if (seen.put(inventory, Boolean.TRUE) == null) {
                inventories.add(inventory);
            }
        }
        for (int i = 0; i < reads.size(); i++) {
            RootInventory inventory = reads.get(i).inventory();
            if (seen.put(inventory, Boolean.TRUE) == null) {
                inventories.add(inventory);
            }
        }
        inventories.sort(Comparator.comparingLong(RootInventory::lockOrder));
        return inventories;
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
     * @param notified 已经领到事件的 Inventory, 跨轮共用以避免重复通知
     * @return 本轮需要新发送的 Inventory 更新事件
     */
    @NotNull
    private static List<TransactionNotification> prepareUpdates(
            @NotNull UpdateReason reason,
            @NotNull List<TransactionScope> scopes,
            @NotNull List<RootInventoryChange> rootChanges,
            boolean includePre,
            @NotNull IdentityHashMap<InventoryUpdateChannel, Boolean> notified
    ) {
        // 同一个 Inventory 可能登记在多个 RootInventory 中, 这里只保留一份.
        List<InventoryUpdateChannel> channels = new ArrayList<>();
        for (int i = 0; i < scopes.size(); i++) {
            scopes.get(i).inventory().collectUpdateChannels(channels, notified);
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
}
