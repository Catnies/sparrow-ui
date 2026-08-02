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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Inventory 事务引擎: 所有 Inventory 写操作最终都汇到这里, 由它保证一笔事务要么全部生效, 要么全部不生效.
 * <p>一笔事务走四步: plan 由调用方先做完(在规划内容上算好每个槽改成什么) → pre 在不持锁的状态下
 * 询问观察者, 任何一个观察者都能取消整笔事务 → commit 在锁内核对规划基准状态引用是否仍然相同,
 * 核对通过才换上新内容 → post 释放锁后把事件按提交顺序派出去. 一笔事务涉及多个 RootInventory 时,
 * 按每个 Inventory 创建时领到的固定序号依次加锁, 即便多线程同时跑跨 Inventory 事务也不会死锁.
 */
final class InventoryTransactions {

    /**
     * 一个 RootInventory 的写集: 要更改的槽位和规划时读取的状态引用.
     *
     * @param inventory 参与事务的 RootInventory
     * @param planned 规划基准状态引用, commit 时只比较引用而不比较内容;
     *                引用变化说明规划后已有其他事务提交
     * @param deltas 该 RootInventory 的槽位变更, 构造后不可变
     */
    record Scope(
            @NotNull RootInventory inventory,
            @Nullable ItemStack @NotNull [] planned,
            @NotNull List<SlotChange> deltas
    ) {
        Scope {
            deltas = List.copyOf(deltas);
        }
    }

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
    static TransactionResult commit(@NotNull UpdateReason reason, @NotNull List<Scope> scopes, boolean bypassPre) {
        List<Scope> declared = validateAndMerge(scopes);
        TransactionDraft draft = new TransactionDraft(declared);

        // 在调用提交前处理器之前, 先记住本笔事务需要通知的所有订阅者.
        boolean cancelled = false;
        List<InventoryUpdateChannel.Prepared> updates = prepareUpdates(reason, declared, draft.rootChanges(), !bypassPre);

        // 按顺序派发 PreUpdateEvent, 每个 Inventory 处理后的取消状态会交给下一个 Inventory.
        if (!bypassPre) {
            for (int i = 0; i < updates.size(); i++) {
                cancelled = updates.get(i).publishPre(cancelled, draft);
            }
            if (cancelled) {
                return TransactionResult.Cancelled.INSTANCE;
            }
        }

        List<Scope> declaredFinal = draft.scopes();
        List<Scope> ordered = sortByLockOrder(declaredFinal);
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
                Scope scope = ordered.get(i);
                if (scope.inventory().currentState() != scope.planned()) {
                    return TransactionResult.Conflicted.INSTANCE;
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
                InventoryTransactions.Scope scope = ordered.get(i);
                afterCommitFailure = ThrowableUtils.captureUnchecked(
                        afterCommitFailure,
                        () -> scope.inventory().afterCommit(scope.deltas())
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
        return new TransactionResult.Committed(rootChanges);
    }

    /**
     * 校验事务, 再把指向同一 RootInventory 的多个写集合并成一个.
     *
     * @param scopes 调用方声明的 RootInventory 写集
     * @return 合并后的 RootInventory 写集, 保持首次出现的声明顺序
     * @throws IllegalArgumentException 当事务形状非法或存在冲突写入时
     */
    @NotNull
    private static List<Scope> validateAndMerge(List<Scope> scopes) {
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("transaction requires at least one scope");
        }
        for (int i = 0; i < scopes.size(); i++) {
            Scope scope = scopes.get(i);
            if (scope.deltas().isEmpty()) {
                throw new IllegalArgumentException("transaction scope has no slot slotChanges");
            }
            int size = scope.planned().length;
            for (int j = 0; j < scope.deltas().size(); j++) {
                int slot = scope.deltas().get(j).slot();
                if (slot < 0 || slot >= size) {
                    throw new IllegalArgumentException("slot " + slot + " is out of bounds for inventory size " + size);
                }
            }
        }

        // 按 RootInventory 归并, LinkedHashMap 保住声明首现顺序
        LinkedHashMap<RootInventory, Scope> mergedByRoot = new LinkedHashMap<>();
        for (int i = 0; i < scopes.size(); i++) {
            Scope scope = scopes.get(i);
            Scope previous = mergedByRoot.get(scope.inventory());
            if (previous == null) {
                mergedByRoot.put(scope.inventory(), scope);
                continue;
            }

            if (previous.planned() != scope.planned()) {
                throw new IllegalArgumentException("transaction contains the same inventory with different planned snapshots");
            }
            List<SlotChange> combined = new ArrayList<>(previous.deltas());
            combined.addAll(scope.deltas());
            mergedByRoot.put(scope.inventory(), new Scope(scope.inventory(), scope.planned(), combined));
        }

        // 合并后的同槽重复意味着两个来源对同一个 RootInventory 槽位给出冲突写入, 是调用方缺陷
        List<Scope> merged = List.copyOf(mergedByRoot.values());
        for (int i = 0; i < merged.size(); i++) {
            Scope scope = merged.get(i);
            HashSet<Integer> seenSlots = new HashSet<>();
            for (int j = 0; j < scope.deltas().size(); j++) {
                if (!seenSlots.add(scope.deltas().get(j).slot())) {
                    throw new IllegalArgumentException("transaction contains conflicting slotChanges for slot " + scope.deltas().get(j).slot());
                }
            }
        }

        // 单个 RootInventory 的 SlotKey 映射在构造时保证一对一; 只有多个 RootInventory 可能指向相同的外部 SlotKey.
        if (merged.size() > 1) {
            HashSet<SlotKey> seenPhysicalSlots = new HashSet<>();
            for (int i = 0; i < merged.size(); i++) {
                Scope scope = merged.get(i);
                for (int j = 0; j < scope.deltas().size(); j++) {
                    int slot = scope.deltas().get(j).slot();
                    if (!seenPhysicalSlots.add(scope.inventory().physicalKey(slot))) {
                        throw new IllegalArgumentException("transaction contains conflicting slotChanges for the same physical slot");
                    }
                }
            }
        }
        return merged;
    }

    /**
     * 按固定顺序排列参与事务的 Inventory, 避免并发事务互相等待.
     * <p>这个顺序只用于加锁和写入. 事件中的变化仍保持调用方传入的顺序.
     *
     * @param declared 按调用方传入顺序排列的修改内容
     * @return 按加锁顺序排列的新列表
     */
    @NotNull
    private static List<Scope> sortByLockOrder(List<Scope> declared) {
        List<Scope> ordered = new ArrayList<>(declared);
        ordered.sort(Comparator.comparingLong(scope -> scope.inventory().lockOrder()));
        return ordered;
    }

    /**
     * 整理本笔事务在每个 RootInventory 中修改了哪些槽位.
     *
     * @param scopes 各 RootInventory 要修改的槽位
     * @return 按传入顺序排列的完整修改列表
     */
    @NotNull
    private static List<RootInventoryChange> changesOf(List<Scope> scopes) {
        List<RootInventoryChange> changes = new ArrayList<>(scopes.size());
        for (int i = 0; i < scopes.size(); i++) {
            Scope scope = scopes.get(i);
            changes.add(new RootInventoryChange(scope.inventory(), scope.deltas()));
        }
        return List.copyOf(changes);
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
    private static List<InventoryUpdateChannel.Prepared> prepareUpdates(
            @NotNull UpdateReason reason,
            @NotNull List<Scope> scopes,
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
        List<InventoryUpdateChannel.Prepared> updates = new ArrayList<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            InventoryUpdateChannel.Prepared update = channels.get(i).prepare(reason, rootChanges, includePre);
            if (update != null) {
                updates.add(update);
            }
        }
        return updates;
    }

    // 复制当前内部状态版本, 再应用槽位变更, 得到新的内部状态版本.
    private static @Nullable ItemStack @NotNull [] applyDeltas(Scope scope) {
        @Nullable ItemStack[] next = scope.inventory().currentState().clone();
        List<SlotChange> deltas = scope.deltas();
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            next[delta.slot()] = delta.rawAfter();
        }
        return next;
    }

    /**
     * 保存 Pre 阶段正在编辑的整笔事务候选值.
     * <p>参与 RootInventory 及其规划基准引用固定, 每个成功处理器只能替换或扩展这些 RootInventory 内的写集.
     */
    static final class TransactionDraft {
        private List<Scope> scopes;
        private List<RootInventoryChange> rootChanges;
        private final ItemStack[][] plannedStates;

        private TransactionDraft(@NotNull List<Scope> scopes) {
            this.scopes = scopes;
            this.rootChanges = changesOf(scopes);
            this.plannedStates = new ItemStack[scopes.size()][];
            for (int i = 0; i < scopes.size(); i++) {
                this.plannedStates[i] = scopes.get(i).planned();
            }
        }

        @NotNull
        List<Scope> scopes() {
            return this.scopes;
        }

        @NotNull
        List<RootInventoryChange> rootChanges() {
            return this.rootChanges;
        }

        @NotNull
        ItemStack[][] plannedStates() {
            return this.plannedStates;
        }

        void accept(@NotNull List<RootInventoryChange> rootChanges) {
            if (rootChanges == this.rootChanges) {
                return;
            }
            if (rootChanges.size() != this.scopes.size()) {
                throw new IllegalArgumentException("pre-update edit changed the participating RootInventory count");
            }

            List<Scope> rewritten = new ArrayList<>(this.scopes.size());
            for (int i = 0; i < this.scopes.size(); i++) {
                Scope scope = this.scopes.get(i);
                RootInventoryChange rootChange = rootChanges.get(i);
                if (rootChange.inventory() != scope.inventory()) {
                    throw new IllegalArgumentException("pre-update edit changed a participating RootInventory");
                }
                rewritten.add(new Scope(scope.inventory(), scope.planned(), rootChange.slotChanges()));
            }
            List<Scope> validated = validateAndMerge(rewritten);
            this.scopes = validated;
            this.rootChanges = changesOf(validated);
        }
    }
}
