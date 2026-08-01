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
 * Inventory事务引擎: 所有Inventory写操作最终都汇到这里, 由它保证一笔事务要么全部生效, 要么全部不生效.
 * <p>一笔事务走四步: plan 由调用方先做完(在快照上算好每个槽改成什么) → pre 在不持锁的状态下
 * 询问观察者, 任何一个观察者都能取消整笔事务 → commit 在锁内核对"规划用的快照没被别人改过",
 * 核对通过才换上新内容 → post 放锁之后把事件按提交顺序派出去. 一笔事务涉及多个Inventory时,
 * 按每个 Inventory 创建时领到的固定序号依次加锁, 即便多线程同时跑跨 Inventory 事务也不会死锁.
 */
final class InventoryTransactions {

    /**
     * Inventory在事务里的参与份额: 计划更改槽数据, 规划时看到的快照数据.
     *
     * @param inventory 参与的Inventory
     * @param planned plan 阶段读到的快照引用, commit 时只比引用不比内容,
     *                引用变了就说明在计划途中有人已经提交了事务
     * @param deltas 该Inventory的槽位变更, 构造后不可变
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
     * @param scopes 参与Inventory与各自要改的槽位; 视图场景下同一根Inventory出现多次是合法的, 内部会合并
     * @param bypassPre 为 {@code true} 时跳过 pre 阶段的询问, 谁也取消不了这笔事务 (post 事件照常派发)
     * @return 事务结果; 只要不是 Committed, 所有参与Inventory都保持原样
     * @throws IllegalArgumentException 当事务形状非法时(没有参与Inventory, 某个范围没有变更, 槽号越界, 同一个槽被写两次)
     * @throws RuntimeException 当提交后的根钩子失败时; 此时镜像状态已经提交, 异常不表示零变更
     * @throws Error 当提交后的根钩子失败时; 此时镜像状态已经提交, 异常不表示零变更
     */
    @NotNull
    static TransactionResult commit(@NotNull UpdateReason reason, @NotNull List<Scope> scopes, boolean bypassPre) {
        List<Scope> declared = validateAndMerge(scopes);
        List<Scope> ordered = sortByLockOrder(declared);
        List<RootInventoryChange> rootChanges = changesOf(declared);

        // 在调用提交前处理器之前, 先记住本笔事务需要通知的所有订阅者.
        boolean cancelled = false;
        List<InventoryUpdateChannel.Prepared> updates = prepareUpdates(reason, declared, rootChanges, !bypassPre);

        // 按顺序派发 PreUpdateEvent, 每个 Inventory 处理后的取消状态会交给下一个 Inventory.
        if (!bypassPre) {
            for (int i = 0; i < updates.size(); i++) {
                cancelled = updates.get(i).publishPre(cancelled);
            }
            if (cancelled) {
                return TransactionResult.Cancelled.INSTANCE;
            }
        }

        int locked = 0;
        try {
            // 按全序逐把加锁, 消除跨Inventory事务的死锁可能
            for (; locked < ordered.size(); locked++) {
                ordered.get(locked).inventory().writeLock().lock();
            }

            // 乐观校验: 任一快照引用已变说明有并发提交插入, 整体放弃
            for (int i = 0; i < ordered.size(); i++) {
                Scope scope = ordered.get(i);
                if (scope.inventory().currentState() != scope.planned()) {
                    return TransactionResult.Conflicted.INSTANCE;
                }
            }

            // 先为全部 Inventory 构造新快照再统一交换, 保证越界等非意料内的异常发生时能够复原.
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
        // 因此提交后处理器运行时能够读到最新内容. 一个根失败也不能跳过其他根, 最后再统一抛出异常.
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
     * 校验事务, 再把指向同一 RootInventory 的多个范围合并成一个.
     *
     * @param scopes 调用方声明的参与范围
     * @return 合并后的参与范围, 保持首次出现的声明顺序
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

        // 按根Inventory归并, LinkedHashMap 保住声明首现顺序
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

        // 合并后的同槽重复意味着两个来源对同一物理槽给出冲突写入, 是调用方缺陷
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

        // 单根Inventory的物理映射在构造时保证一对一;只有多个根可能跨镜像写入同一个外部槽.
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
     * 当前 Inventory 没有可见变化或没有订阅者时, 不会创建对应事件.
     *
     * @param reason 事务触发原因
     * @param scopes 本笔事务修改到的 RootInventory
     * @param rootChanges 整笔事务在 RootInventory 中的变化
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

        // 记住当前订阅者, 并把根槽位编号转换成各 Inventory 自己的槽位编号.
        List<InventoryUpdateChannel.Prepared> updates = new ArrayList<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            InventoryUpdateChannel.Prepared update = channels.get(i).prepare(reason, rootChanges, includePre);
            if (update != null) {
                updates.add(update);
            }
        }
        return updates;
    }

    // 把变更落到一张新快照上, 复制当前快照, 再把发生变化的槽位换成新物品.
    private static @Nullable ItemStack @NotNull [] applyDeltas(Scope scope) {
        @Nullable ItemStack[] next = scope.inventory().currentState().clone();
        List<SlotChange> deltas = scope.deltas();
        for (int i = 0; i < deltas.size(); i++) {
            SlotChange delta = deltas.get(i);
            next[delta.slot()] = delta.rawAfter();
        }
        return next;
    }
}
