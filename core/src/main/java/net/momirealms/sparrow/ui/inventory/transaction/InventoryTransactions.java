package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.TransactionResult;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

// Inventory 事务引擎, 跨 Inventory 提交使用固定锁序.
@ApiStatus.Internal
public final class InventoryTransactions {
    private static final VersionSource VERSION_SOURCE = new VersionSource(System::currentTimeMillis);
    private static final ThreadLocal<ArrayDeque<Runnable>> POST_DISPATCH = new ThreadLocal<>();

    private InventoryTransactions() {
    }

    /**
     * 提交一笔事务.
     *
     * @param reason 变更原因
     * @param scopes 各 Inventory 写集; 同一个 Inventory 至多出现一次
     * @param bypassPre 是否跳过可取消的 pre 阶段, post 仍会派发
     * @return 事务结果; 只要不是 Committed, 所有参与 Inventory 都保持原样
     * @throws IllegalArgumentException 当事务形状非法时(没有 Inventory 写集, 某个写集没有变更, 槽号越界, 同一个槽被写两次, 同一个 Inventory 出现两次)
     * @throws RuntimeException 当提交后处理失败时; 此时 Sparrow 内部状态已经提交, 异常不表示零变更
     * @throws Error 当提交后处理失败时; 此时 Sparrow 内部状态已经提交, 异常不表示零变更
     */
    @NotNull
    public static TransactionResult commit(@NotNull UpdateReason reason, @NotNull List<TransactionScope> scopes, boolean bypassPre) {
        return commit(reason, new TransactionDraft(scopes), null, bypassPre, null, List.of(), () -> true);
    }

    /**
     * 提交一笔草稿已经在事务外准备好的事务.
     * <p>Bukkit 事件, Sparrow 事件和 Pre 处理器依次修改同一份草稿.
     * {@code commitGuard} 在 Pre 之后, 加锁之前执行, 返回 false 时按冲突处理.
     * <p><strong>调用方必须提前刷新 readSet</strong>. 提交过程中刷新引用存储会派发嵌套的 External 事务.
     *
     * @param reason 变更原因
     * @param draft 已经校验过形状的写集草稿
     * @param interaction 触发本笔事务的交互副作用草稿, 非玩家交互传 {@code null}
     * @param bypassPre 为 {@code true} 时跳过 pre 阶段的询问
     * @param committedCallback 状态提交后, Post 派发前执行的回调
     * @param readSet 只做乐观校验的额外读集
     * @param commitGuard 取得写锁前的最终提交条件
     * @return 事务结果; 只要不是 Committed, 所有参与 Inventory 都保持原样
     */
    @NotNull
    public static TransactionResult commit(
            @NotNull UpdateReason reason,
            @NotNull TransactionDraft draft,
            @Nullable InteractionDraft interaction,
            boolean bypassPre,
            @Nullable Runnable committedCallback,
            @NotNull List<PlannedRoot> readSet,
            @NotNull BooleanSupplier commitGuard
    ) {
        return new Commit(reason, draft, interaction, bypassPre, committedCallback, readSet, commitGuard, true).run();
    }

    /**
     * 提交一笔内容取自外部存储的同步事务, 供 ReferencingInventory 把外部世界的既成变更派发出去.
     * <p>目标内容已经存在于外部存储, 因此跳过 pre 阶段和提交后的回写.
     *
     * @param scope 本笔同步事务的写集
     * @return 事务结果; 只要不是 Committed, 参与的 Inventory 就保持原样
     */
    @NotNull
    public static TransactionResult commitExternalSync(@NotNull TransactionScope scope) {
        return new Commit(UpdateReason.External.INSTANCE, new TransactionDraft(List.of(scope)), null, true, null, List.of(), () -> true, false).run();
    }

    // 权威命令先取得写权限再规划; 回调只计算本次变更, 不得重入库存写入或访问其他库存.
    // 引用存储依赖调用方的所属线程串行保证, 写前刷新在临界区外完成.
    public static <P> P mutate(
            @NotNull UpdateReason reason,
            @NotNull SparrowInventory inventory,
            @NotNull Function<PlannedRoot, P> planner,
            @NotNull Function<P, List<SlotChange>> changes
    ) {
        inventory.prepareWrite();
        if (inventory.retired()) {
            throw new IllegalStateException("Cannot modify a retired inventory");
        }
        @Nullable PlannedRoot.StateLock lock = inventory.stateLock();
        P plan;
        Commit commit;
        if (lock != null) {
            lock.lock().lock();
        }
        try {
            PlannedRoot basis = inventory.openPlan();
            plan = planner.apply(basis);
            List<SlotChange> deltas = changes.apply(plan);
            if (deltas.isEmpty()) {
                return plan;
            }
            commit = new Commit(reason, new TransactionDraft(List.of(new TransactionScope(basis, deltas))), null, true, null, List.of(), () -> true, true);
            int declaredCount = commit.prepareDeclaredUpdates();
            commit.seal(declaredCount);
            commit.swapStates();
        } finally {
            if (lock != null) {
                lock.lock().unlock();
            }
        }
        commit.landAndNotify();
        return plan;
    }

    private static final class Commit {
        // 事务输入与提交策略
        private final UpdateReason reason;
        private final TransactionDraft draft;
        @Nullable private final InteractionDraft interaction;
        private final boolean bypassPre;
        @Nullable private final Runnable committedCallback;
        private final List<PlannedRoot> readSet;
        private final BooleanSupplier commitGuard;
        private final boolean writeBack; // 为 true 时在状态提交后调用各基准的落地, 把内容写进外部存储
        // 流水线准备结果
        private List<TransactionNotification> updates;              // 本笔事务需要通知的订阅者
        private List<TransactionScope> scopes;                      // 封笔后的最终写集
        private long version;                                       // 状态交换成功后取得的事务逻辑版本

        private Commit(
                UpdateReason reason,
                TransactionDraft draft,
                @Nullable InteractionDraft interaction,
                boolean bypassPre,
                @Nullable Runnable committedCallback,
                List<PlannedRoot> readSet,
                BooleanSupplier commitGuard,
                boolean writeBack
        ) {
            this.reason = reason;
            this.draft = draft;
            this.interaction = interaction;
            this.bypassPre = bypassPre;
            this.committedCallback = committedCallback;
            this.readSet = readSet;
            this.commitGuard = commitGuard;
            this.writeBack = writeBack;
        }

        // 流水线依次是 冻结兜底 -> 记下订阅者 -> pre 链 -> commitGuard -> 封笔 -> 锁内校验与交换 -> 落地与 post 派发.
        @NotNull
        TransactionResult run() {
            if (this.hasFrozenPlayerTarget()) {
                return TransactionResult.Cancelled.INSTANCE;
            }
            int declaredCount = this.prepareDeclaredUpdates();
            if (!this.publishPre()) {
                return TransactionResult.Cancelled.INSTANCE;
            }
            if (!this.commitGuard.getAsBoolean()) {
                return TransactionResult.Conflicted.INSTANCE;
            }
            this.seal(declaredCount);
            if (!this.swapUnderLocks()) {
                return TransactionResult.Conflicted.INSTANCE;
            }
            this.landAndNotify();
            return new TransactionResult.Committed(this.draft.rootChanges());
        }

        // Inventory 级冻结兜底, 玩家侧写入在规划层就该被拒, 这里拦住漏网的玩家事务.
        private boolean hasFrozenPlayerTarget() {
            if (!(this.reason instanceof PlayerUpdateReason)) {
                return false;
            }
            List<TransactionScope> scopes = this.draft.scopes();
            for (int i = 0; i < scopes.size(); i++) {
                if (scopes.get(i).inventory().frozen()) {
                    return true;
                }
            }
            return false;
        }

        // 记录原写集长度, 用于识别 Pre 期间追加的参与者.
        private int prepareDeclaredUpdates() {
            List<TransactionScope> declared = this.draft.scopes();
            this.updates = prepareUpdates(this.reason, declared, !this.bypassPre);
            return declared.size();
        }

        // 取消状态沿 Pre 链传递, 最终状态决定整笔事务是否继续.
        private boolean publishPre() {
            if (this.bypassPre) {
                return true;
            }
            boolean cancelled = false;
            for (int i = 0; i < this.updates.size(); i++) {
                cancelled = this.updates.get(i).publishPre(cancelled, this.draft, this.interaction);
            }
            return !cancelled;
        }

        // 草稿冻结后补充新参与者的 Post 接收者, 它们不参与本轮 Pre.
        private void seal(int declaredCount) {
            if (this.interaction != null) {
                this.interaction.seal();
            }
            this.scopes = this.draft.scopes();
            List<TransactionScope> included = this.scopes.subList(declaredCount, this.scopes.size());
            if (!included.isEmpty()) {
                this.updates.addAll(prepareUpdates(this.reason, included, false));
            }
        }

        // 所有基准通过校验后才构造并交换状态.
        private boolean swapUnderLocks() {
            List<PlannedRoot.StateLock> locks = collectLocks(this.scopes, this.readSet);
            int locked = 0;
            try {
                // 固定锁序覆盖写集与读集.
                for (; locked < locks.size(); locked++) {
                    locks.get(locked).lock().lock();
                }

                // 乐观校验. 任一规划基准已失效说明有并发提交插入, 整体放弃.
                for (int i = 0; i < this.scopes.size(); i++) {
                    if (this.scopes.get(i).basis().isStale()) {
                        return false;
                    }
                }
                for (int i = 0; i < this.readSet.size(); i++) {
                    if (this.readSet.get(i).isStale()) {
                        return false;
                    }
                }

                this.swapStates();
                return true;
            } finally {
                for (int i = locked - 1; i >= 0; i--) {
                    locks.get(i).lock().unlock();
                }
            }
        }

        // 调用方已持有全部写权限, 先完成全部构造再交换; 版本和票号沿用同一提交顺序.
        private void swapStates() {
            @Nullable ItemStack[][] staged = new ItemStack[this.scopes.size()][];
            for (int i = 0; i < this.scopes.size(); i++) {
                TransactionScope scope = this.scopes.get(i);
                staged[i] = scope.basis().buildNextState(scope.slotChanges());
            }
            for (int i = 0; i < this.scopes.size(); i++) {
                this.scopes.get(i).basis().swapTo(staged[i]);
            }
            this.version = VERSION_SOURCE.next();
            // 未开启串行派发的 Inventory 不领票号.
            for (int i = 0; i < this.updates.size(); i++) {
                this.updates.get(i).takePostTicket();
            }
        }

        // 提交生效之后的收尾, 依次做落地, 提交回调和 Post 派发.
        private void landAndNotify() {
            Throwable failure = null;
            try {
                // 各参与者独立落地, 单个失败不跳过后续参与者.
                if (this.writeBack) {
                    for (int i = 0; i < this.scopes.size(); i++) {
                        TransactionScope scope = this.scopes.get(i);
                        failure = ThrowableUtils.captureUnchecked(failure, () -> scope.basis().land(scope.slotChanges()));
                    }
                }
                if (this.committedCallback != null) {
                    failure = ThrowableUtils.captureUnchecked(failure, this.committedCallback);
                }
            } finally {
                // <strong>已签发的 Post 票号必须在异常路径上照常放行</strong>.
                dispatchPostBatch(this::publishPost);
            }
            ThrowableUtils.throwIfUnchecked(failure);
        }

        // 所有参与者共享事务版本, 单个派发失败不阻断后续票号.
        private void publishPost() {
            Throwable failure = null;
            for (int i = 0; i < this.updates.size(); i++) {
                TransactionNotification update = this.updates.get(i);
                failure = ThrowableUtils.captureUnchecked(failure, () -> update.publishPost(this.scopes, this.version));
            }
            ThrowableUtils.throwIfUnchecked(failure);
        }
    }

    // 在当前线程排队并派发一整笔事务的 Post 批次.
    private static void dispatchPostBatch(@NotNull Runnable batch) {
        // Post 回调中的嵌套事务排到当前完整批次之后.
        ArrayDeque<Runnable> pending = POST_DISPATCH.get();
        if (pending != null) {
            pending.addLast(batch);
            return;
        }

        pending = new ArrayDeque<>();
        POST_DISPATCH.set(pending);
        try {
            batch.run();
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
            }
        } finally {
            POST_DISPATCH.remove();
        }
    }

    // 挑出本笔事务需要通知的 Inventory, 提前把各自的事件准备好; 从未订阅过的 Inventory 没有通道, 直接略过.
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

    // 固定锁序不改变写入, 落地和事件中的参与者顺序.
    @NotNull
    private static List<PlannedRoot.StateLock> collectLocks(List<TransactionScope> writes, List<PlannedRoot> reads) {
        List<PlannedRoot.StateLock> locks = new ArrayList<>(writes.size() + reads.size());
        IdentityHashMap<SparrowInventory, Boolean> seen = new IdentityHashMap<>();
        for (int i = 0; i < writes.size(); i++) {
            collectLock(writes.get(i).basis(), seen, locks);
        }
        for (int i = 0; i < reads.size(); i++) {
            collectLock(reads.get(i), seen, locks);
        }
        locks.sort(Comparator.comparingLong(PlannedRoot.StateLock::order));
        return locks;
    }

    // 同一个 Inventory 在写集与读集中可能各出现一次, 只取第一份凭证.
    private static void collectLock(
            PlannedRoot root,
            IdentityHashMap<SparrowInventory, Boolean> seen,
            List<PlannedRoot.StateLock> locks
    ) {
        if (seen.put(root.inventory(), Boolean.TRUE) != null) return;
        @Nullable PlannedRoot.StateLock lock = root.stateLock();
        if (lock != null) {
            locks.add(lock);
        }
    }
}
