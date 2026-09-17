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

// Inventory 事务引擎. 一笔事务可以同时改好几个 Inventory, 要么全部生效要么一格都不动.
// 不死锁靠的是固定锁序: 每个 Inventory 出生时领一个全局递增的序号, 加锁一律按序号从小到大.
@ApiStatus.Internal
public final class InventoryTransactions {
    private static final VersionSource VERSION_SOURCE = new VersionSource(System::currentTimeMillis);
    private static final ThreadLocal<ArrayDeque<Runnable>> POST_DISPATCH = new ThreadLocal<>();
    private static final ThreadLocal<SparrowInventory> AUTHORITY_SCOPE = new ThreadLocal<>(); // 本线程正在执行权威命令的 Inventory, 只在写入临界区内有值

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
     * <p><strong>调用方必须提前刷新 readSet</strong>. 提交过程中刷新 ReferencingInventory 会派发嵌套的 External 事务.
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
        checkOutsideAuthorityScope();
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
        checkOutsideAuthorityScope();
        return new Commit(UpdateReason.External.INSTANCE, new TransactionDraft(List.of(scope)), null, true, null, List.of(), () -> true, false).run();
    }

    // 权威命令先取得写权限再规划; 回调只计算本次变更, 重入库存写入会被 AUTHORITY_SCOPE 拦住.
    // ReferencingInventory 这里压根没有锁, 串行全靠调用方只从存储所属线程进来.
    // 写前刷新放在临界区外面做, 它可能派发一笔 External 事务, 不能带进临界区.
    public static <P> P mutate(
            @NotNull UpdateReason reason,
            @NotNull SparrowInventory inventory,
            @NotNull Function<PlannedRoot, P> planner,
            @NotNull Function<P, List<SlotChange>> changes
    ) {
        checkOutsideAuthorityScope();
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
            // 规划基准在这里定下, 到状态交换为止都不复查, 期间任何写入都必须被挡在外面.
            AUTHORITY_SCOPE.set(inventory);
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
            AUTHORITY_SCOPE.remove();
            if (lock != null) {
                lock.lock().unlock();
            }
        }
        // 落地与 Post 已经离开临界区, 处理器里的嵌套事务照常受理.
        commit.landAndNotify();
        return plan;
    }

    // 权威回调运行在写入临界区内. 它再开一笔事务, 外层会按过期基准构造状态并盖掉内层写入,
    // 写到别的 Inventory 还会在固定锁序之外多拿一把锁. 两种后果都不会自己暴露, 因此在入口拦住.
    private static void checkOutsideAuthorityScope() {
        @Nullable SparrowInventory owner = AUTHORITY_SCOPE.get();
        if (owner == null) return;
        throw new IllegalStateException("Cannot start an inventory transaction from inside an authoritative callback of " + owner
                + ". The callback may only compute the item handed to it; move the extra write after the authoritative call returns.");
    }

    private static final class Commit {
        // 这笔事务是什么, 以及该怎么提交
        private final UpdateReason reason;
        private final TransactionDraft draft;
        @Nullable private final InteractionDraft interaction;
        private final boolean bypassPre;
        @Nullable private final Runnable committedCallback;
        private final List<PlannedRoot> readSet;
        private final BooleanSupplier commitGuard;
        private final boolean writeBack; // true 表示状态生效后还要把内容写进外部容器; 外部同步那条路内容本来就在外面, 不用回写
        // 流水线跑到一半攒下来的东西
        private List<TransactionNotification> updates;              // 这笔事务要通知谁, 名单在开头就定死
        private List<TransactionScope> scopes;                      // 封笔之后不再变的最终写集
        private long version;                                       // 状态换上去之后领到的版本号, 同一笔事务的所有 Post 共享它

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

        // 整条流水线的顺序: 冻结兜底 -> 记下订阅者 -> 跑 Pre -> commitGuard -> 封笔 -> 锁内校验并交换 -> 落地和派发 Post.
        // 前五步都在锁外面, 随时可以放弃; 一旦进了第六步并通过校验, 这笔事务就一定会生效.
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

        // 冻结的兜底检查. 玩家侧的写入本该在规划层就被拒掉, 这里再拦一道, 防止有路径绕过去.
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

        // 记下原写集有多长, 之后靠这个长度切出 Pre 期间被 include 进来的新参与者.
        private int prepareDeclaredUpdates() {
            List<TransactionScope> declared = this.draft.scopes();
            this.updates = prepareUpdates(this.reason, declared, !this.bypassPre);
            return declared.size();
        }

        // 取消状态顺着整条 Pre 链传, 最后一个处理器留下的结论决定这笔事务还走不走.
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

        // 草稿封笔. 顺手给 Pre 期间新拉进来的参与者补上 Post 接收者,
        // 它们赶不上本轮 Pre 了, 但照样要收到 Post, 而且不会再递归展开一轮.
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

        // 拿齐锁, 验完所有基准, 才开始构造和交换状态.
        private boolean swapUnderLocks() {
            List<PlannedRoot.StateLock> locks = collectLocks(this.scopes, this.readSet);
            int locked = 0;
            try {
                // 写集和读集里的锁都在这一批里, 按序号升序拿.
                for (; locked < locks.size(); locked++) {
                    locks.get(locked).lock().lock();
                }

                // 乐观校验. 只要有一份基准失效, 就说明中间插进来过别的提交, 整笔放弃而不是硬写.
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
                // 只解自己真拿到的那几把, locked 停在第一把没拿到的锁上.
                // 倒着解不是必须的, 但和加锁顺序对称, 读起来不容易怀疑漏了哪一把.
                for (int i = locked - 1; i >= 0; i--) {
                    locks.get(i).lock().unlock();
                }
            }
        }

        // 到这里调用方已经拿着全部写权限了. 先把每个参与者的新状态全部算完, 再一口气换上去,
        // 中间任何一步抛异常都不会留下半套生效的状态. 版本和票号也在这里领, 跟提交顺序一致.
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
            // 没开串行派发的 Inventory 不领票号, 它的 Post 谁先跑谁跑.
            for (int i = 0; i < this.updates.size(); i++) {
                this.updates.get(i).takePostTicket();
            }
        }

        // 状态已经生效, 剩下的是收尾: 落地到外部容器, 跑提交回调, 派发 Post.
        private void landAndNotify() {
            Throwable failure = null;
            try {
                // 每个参与者各自落地, 一个失败也要把剩下的写完, 异常先攒着最后一起抛.
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

        // 一笔事务里所有参与者共享同一个版本号. 某个派发失败照样往下走, 不然后面的票号会被永远堵住.
        private void publishPost() {
            Throwable failure = null;
            for (int i = 0; i < this.updates.size(); i++) {
                TransactionNotification update = this.updates.get(i);
                failure = ThrowableUtils.captureUnchecked(failure, () -> update.publishPost(this.scopes, this.version));
            }
            ThrowableUtils.throwIfUnchecked(failure);
        }
    }

    // 一笔事务的 Post 作为一整批在当前线程派发完.
    private static void dispatchPostBatch(@NotNull Runnable batch) {
        // 已经在派发批次里了, 说明这是 Post 处理器里又开的一笔事务. 把它排到当前整批之后, 不插队.
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

    // 挑出这笔事务真要通知的 Inventory, 提前把事件对象准备好.
    // 从来没人订阅过的 Inventory 压根没有通道, 不值得为它建一个空的, 直接跳过.
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

    // 收集要加的锁并按序号排好. 加锁顺序只管加锁, 写入、落地和事件里的参与者顺序还是按调用方声明的来.
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

    // 同一个 Inventory 可能在写集和读集里各出现一次, 锁只取第一份, 重复加锁没意义.
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
