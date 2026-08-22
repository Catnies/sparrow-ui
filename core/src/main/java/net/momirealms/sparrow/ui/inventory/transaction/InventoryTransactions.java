package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.TransactionResult;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
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

// Inventory 事务引擎.
// 一笔事务大致分为四段, plan (在规划内容上算好每个槽改成什么) -> pre (预处理器) -> commit (核对每条规划基准) -> post (后处理器).
// 涉及多个 Inventory 时按锁凭证的固定序号逐把加锁, 多线程同时跑跨 Inventory 事务也不会死锁, 不加锁的那一种靠调用方串行访问.
@ApiStatus.Internal
public final class InventoryTransactions {
    private static final VersionSource VERSION_SOURCE = new VersionSource(System::currentTimeMillis); // 成功事务的全局逻辑版本源
    private static final ThreadLocal<ArrayDeque<Runnable>> POST_DISPATCH = new ThreadLocal<>(); // 当前线程等待派发的 Post 批次

    private InventoryTransactions() {
    }

    /**
     * 提交一笔事务.
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
    public static TransactionResult commit(@NotNull UpdateReason reason, @NotNull List<TransactionScope> scopes, boolean bypassPre) {
        return commit(reason, new TransactionDraft(scopes), null, bypassPre, null, List.of(), () -> true);
    }

    /**
     * 提交一笔草稿已经在事务外准备好的事务.
     * <p>玩家交互走这条入口. 写集草稿在第一道闸门之前就要建好, 让 Bukkit 事件, Sparrow 事件和 Pre 处理器
     * 依次写进同一份草稿, 上一个监听器的结果留给下一个.
     * <p>最终提交条件在 Pre 完成后、取得 Inventory 写锁前检查; 返回 false 时按冲突处理,
     * 整笔事务保持零变更. 条件运行在锁外, 可以安全复核玩家与 Window 状态.
     * <p>读集只在锁内做基准状态引用的乐观校验, 本方法不会刷新它.
     * 调用方必须在调用前自行完成刷新. 事务中段调用 {@link SparrowInventory#prepareWrite()} 会让
     * ReferencingInventory 提交一笔嵌套的 External 事务并派发它自己的 Post, 相当于在本笔事务的
     * Pre 与 commit 之间重入事件系统.
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
     * <p>这类事务的目标内容就是刚从外部存储读到的内容, 因此跳过 pre 阶段, 也跳过提交后的落地.
     * 回写只会用等值副本换掉存储里的物品实例, 作废外部持有的引用.
     *
     * @param scope 本笔同步事务的写集
     * @return 事务结果; 只要不是 Committed, 参与的 Inventory 就保持原样
     */
    @NotNull
    public static TransactionResult commitExternalSync(@NotNull TransactionScope scope) {
        return new Commit(UpdateReason.External.INSTANCE, new TransactionDraft(List.of(scope)), null, true, null, List.of(), () -> true, false).run();
    }

    // 一笔事务的提交过程, 输入和跨相位的过程状态收成字段, 每个相位一个方法, 由 run 按流水线串起来.
    private static final class Commit {
        private final UpdateReason reason;
        private final TransactionDraft draft;
        @Nullable private final InteractionDraft interaction;
        private final boolean bypassPre;
        @Nullable private final Runnable committedCallback;
        private final List<PlannedRoot> readSet;
        private final BooleanSupplier commitGuard;
        private final boolean writeBack; // 为 true 时在状态提交后调用各基准的落地, 把内容写进外部存储

        private List<TransactionNotification> updates;              // 本笔事务需要通知的订阅者
        private List<TransactionScope> scopes;                      // 封笔后的最终写集
        private List<PlannedRoot.StateLock> locks;                  // 按全序排好的锁凭证
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
            return this.landAndNotify();
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

        // 在调用提交前处理器之前, 先记住本笔事务需要通知的所有订阅者.
        // 返回此刻的写集数量, 封笔时以它区分原有参与者与 Pre 期间新纳入的 Inventory.
        private int prepareDeclaredUpdates() {
            List<TransactionScope> declared = this.draft.scopes();
            this.updates = prepareUpdates(this.reason, declared, !this.bypassPre);
            return declared.size();
        }

        // 按顺序派发 PreUpdateEvent, 每个 Inventory 处理后的取消状态会交给下一个 Inventory.
        // 任何一个观察者取消都让整笔事务零变更.
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

        // 最终条件已经通过, 两份草稿同时封笔, 之后的加锁写入必须看到定案的内容.
        // Pre 期间新纳入的 Inventory 没有参加本轮 Pre, 但它们的订阅者照样要收到 Post.
        // 已参与的 Inventory 只可追加, 不可移除也不可换位, 新参与者就是写集末尾多出来的那一段.
        private void seal(int declaredCount) {
            if (this.interaction != null) {
                this.interaction.seal();
            }
            this.scopes = this.draft.scopes();
            this.locks = collectLocks(this.scopes, this.readSet);
            List<TransactionScope> included = this.scopes.subList(declaredCount, this.scopes.size());
            if (!included.isEmpty()) {
                this.updates.addAll(prepareUpdates(this.reason, included, false));
            }
        }

        // 临界区里依次做加锁, 乐观校验, 构造并交换新状态, 取得事务版本. 任一基准失效返回 false, 整体零变更.
        private boolean swapUnderLocks() {
            int locked = 0;
            try {
                // 按全序逐把加锁, 消除跨 Inventory 事务的死锁可能; 不加锁的基准没有凭证, 不在列表里.
                for (; locked < this.locks.size(); locked++) {
                    this.locks.get(locked).lock().lock();
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

                // 先为全部写集构造提交产物, 再一次性交换, 中途出意外时状态还是原来那一份.
                // 不需要交换状态的基准产物为 null, 交换时无事发生.
                @Nullable ItemStack[][] staged = new ItemStack[this.scopes.size()][];
                for (int i = 0; i < this.scopes.size(); i++) {
                    TransactionScope scope = this.scopes.get(i);
                    staged[i] = scope.basis().buildNextState(scope.slotChanges());
                }
                for (int i = 0; i < this.scopes.size(); i++) {
                    this.scopes.get(i).basis().swapTo(staged[i]);
                }
                // 所有状态都已交换且根锁尚未释放, 此处取得的版本与共享根上的提交线性化顺序一致.
                this.version = VERSION_SOURCE.next();
                // 票号同样在这里领. 只有临界区内领到的号才等于提交顺序, 未开启串行派发的 Inventory 不领号.
                for (int i = 0; i < this.updates.size(); i++) {
                    this.updates.get(i).takePostTicket();
                }
                return true;
            } finally {
                for (int i = locked - 1; i >= 0; i--) {
                    this.locks.get(i).lock().unlock();
                }
            }
        }

        // 提交生效之后的收尾, 依次做落地, 提交回调和 Post 派发.
        @NotNull
        private TransactionResult landAndNotify() {
            Throwable failure = null;
            try {
                // 内容放在外部存储的 Inventory 在这里把变更写进存储, 提交后处理器读到的就是最新内容.
                // 逐个捕获, 一个 Inventory 失败也不能跳过后面那些.
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
                // 无论成败都要走到派发. 领了票号就要叫号, 串行 Inventory 之后的提交线程都在等这一次放行.
                dispatchPostBatch(this::publishPost);
            }
            // 落地与回调途中攒下的异常留到这里一起抛.
            ThrowableUtils.throwIfUnchecked(failure);
            return new TransactionResult.Committed(this.draft.rootChanges());
        }

        // 向本笔事务涉及的全部 Inventory 发送同一版本的 Post 事件.
        // 逐个捕获, 一个 Inventory 失败也不能让后面的 Inventory 拿着票号退出.
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
        // 已经有人在排空了, 说明自己是 Post 回调里的嵌套事务, 只管把批次挂到队尾, 由最外层那次调用接着排空
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

    // 按固定顺序排出本笔事务要拿的锁凭证, 这个顺序只管加锁.
    // 写入, 落地和事件里的变化仍然是调用方传入的顺序.
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
