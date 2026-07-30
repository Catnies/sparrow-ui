package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.InventoryDelta;
import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Inventory事务引擎: 所有Inventory写操作最终都汇到这里, 由它保证一笔事务要么全部生效, 要么全部不生效.
 * <p>一笔事务走四步: plan 由调用方先做完(在快照上算好每个槽改成什么) → pre 在不持锁的状态下
 * 询问观察者, 任何一个观察者都能取消整笔事务 → commit 在锁内核对"规划用的快照没被别人改过",
 * 核对通过才换上新内容 → post 放锁之后把事件按提交顺序派出去. 一笔事务涉及多个Inventory时,
 * 按每个Inventory创建时领到的固定序号依次加锁, 即便多线程同时跑跨Inventory事务也不会死锁.
 */
final class InventoryTransactions {

    /**
     * Inventory在事务里的参与份额:计划更改槽数据, 规划时看到的快照数据.
     *
     * @param inventory 参与的Inventory
     * @param planned plan 阶段读到的快照引用, commit 时只比引用不比内容,
     *                引用变了就说明在计划途中有人已经提交了事务
     * @param deltas 该Inventory的槽位变更, 构造后不可变
     */
    record Scope(
            @NotNull AbstractInventory inventory,
            @Nullable ItemStack @NotNull [] planned,
            @NotNull List<SlotDelta> deltas
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
     * @param bypassPre 为 {@code true} 时跳过 pre 阶段的询问, 谁也取消不了这笔事务(post 事件照常派发)
     * @return 事务结果; 只要不是 Committed, 所有参与Inventory都保持原样
     * @throws IllegalArgumentException 当事务形状非法时(没有参与Inventory, 某个范围没有变更, 槽号越界, 同一个槽被写两次)
     */
    @NotNull
    static TransactionResult commit(@NotNull UpdateReason reason, @NotNull List<Scope> scopes, boolean bypassPre) {
        // 声明序列表负责事件载荷, 锁序列表只服务于加锁与提交, 两个顺序自此解耦
        List<Scope> declared = validateAndMerge(scopes);
        List<Scope> ordered = sortByLockOrder(declared);
        List<InventoryDelta> changes = changesOf(declared);

        // 不可访问的外部根在用户回调前拒绝, 不产生 pre/post 或任何镜像变更
        if (!writeAvailable(ordered)) {
            return TransactionResult.Unavailable.INSTANCE;
        }

        // pre 阶段: 锁外对每个参与根派发一次, 任一观察者取消则整个事务零变更结束
        if (!bypassPre) {
            InventoryPreUpdateEvent preEvent = new InventoryPreUpdateEvent(reason, changes);
            for (int i = 0; i < ordered.size(); i++) {
                ordered.get(i).inventory().publishPreUpdate(preEvent);
            }
            if (preEvent.cancelled()) {
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

            // pre 回调可能移动实体或改变 owner; 在任何新状态构造与交换前重新校验
            if (!writeAvailable(ordered)) {
                return TransactionResult.Unavailable.INSTANCE;
            }

            // 先为全部Inventory构造新快照再统一交换, 保证越界等非意料内的异常发生时零交换
            InventoryPostUpdateEvent postEvent = new InventoryPostUpdateEvent(reason, changes);
            @Nullable ItemStack[][] newStates = new ItemStack[ordered.size()][];
            for (int i = 0; i < ordered.size(); i++) {
                newStates[i] = applyDeltas(ordered.get(i));
            }
            for (int i = 0; i < ordered.size(); i++) {
                ordered.get(i).inventory().swapState(newStates[i]);
            }

            // 锁内入队使 post 顺序与快照交换顺序一致, 实际派发推迟到放锁之后
            for (int i = 0; i < ordered.size(); i++) {
                ordered.get(i).inventory().enqueuePostEvent(postEvent);
            }
        } finally {
            for (int i = locked - 1; i >= 0; i--) {
                ordered.get(i).inventory().writeLock().unlock();
            }
        }

        // 提交后先于 post 派发: 镜ReferencingInventory 根在此把变更写回外部容器,
        // 使 post 观察者重入写时外部状态已同步; 异常隔离上报, 不影响已提交的事务结果
        for (int i = 0; i < ordered.size(); i++) {
            InventoryTransactions.Scope scope = ordered.get(i);
            try {
                scope.inventory().afterCommit(scope.deltas());
            } catch (Throwable exception) {
                SparrowUI.getInstance().handleException("Failed to run Inventory after-commit hook", exception);
            }
        }

        // post 阶段: 锁外排水, 观察者异常已在排水路径内隔离
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).inventory().drainPostEvents();
        }
        return new TransactionResult.Committed(changes);
    }

    /**
     * 检查这笔事务的每个参与Inventory此刻是否都允许写入.
     * <p>它在用户回调前后各被查一次, 因为 pre 观察者的回调可能把容器搬到别的线程, 前后的答案未必一样.
     *
     * @param scopes 按加锁顺序排列的参与范围
     * @return 全部可写返回 {@code true}
     */
    private static boolean writeAvailable(List<Scope> scopes) {
        for (int i = 0; i < scopes.size(); i++) {
            if (!scopes.get(i).inventory().writeAvailable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验事务, 再把指向同一根Inventory的多个范围合并成一个.
     * <p>返回列表保持声明首现顺序, 用于保障"按调用方声明顺序".
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
                throw new IllegalArgumentException("transaction scope has no slot deltas");
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
        LinkedHashMap<AbstractInventory, Scope> mergedByRoot = new LinkedHashMap<>();
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
            List<SlotDelta> combined = new ArrayList<>(previous.deltas());
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
                    throw new IllegalArgumentException("transaction contains conflicting deltas for slot " + scope.deltas().get(j).slot());
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
                        throw new IllegalArgumentException("transaction contains conflicting deltas for the same physical slot");
                    }
                }
            }
        }
        return merged;
    }

    /**
     * 把参与范围按各Inventory的锁序号排成全局唯一的加锁顺序.
     * <p>加锁, 冲突核对, 换快照都按这个顺序进行; 事件载荷的顺序与此无关, 仍按声明顺序.
     *
     * @param declared 按声明顺序排列的参与范围
     * @return 按锁序号重新排序后的参与范围
     */
    @NotNull
    private static List<Scope> sortByLockOrder(List<Scope> declared) {
        List<Scope> ordered = new ArrayList<>(declared);
        ordered.sort(Comparator.comparingLong(scope -> scope.inventory().lockOrder()));
        return ordered;
    }

    /**
     * 组装事件的变更载荷: 每个参与Inventory一条变更记录, 按调用方声明顺序排列, 与加锁顺序无关.
     *
     * @param scopes 按声明顺序排列的参与范围
     * @return 事件载荷, 不可变列表
     */
    @NotNull
    private static List<InventoryDelta> changesOf(List<Scope> scopes) {
        List<InventoryDelta> changes = new ArrayList<>(scopes.size());
        for (int i = 0; i < scopes.size(); i++) {
            Scope scope = scopes.get(i);
            changes.add(new InventoryDelta(scope.inventory(), scope.deltas()));
        }
        return List.copyOf(changes);
    }

    /**
     * 把变更落到一张新快照上: 复制当前快照, 再把发生变化的槽位换成新物品.
     * <p>没动的槽位与旧快照共享同一个物品实例.
     *
     * @param scope 单个Inventory的参与范围
     * @return 应用变更后的新快照
     */
    private static @Nullable ItemStack @NotNull [] applyDeltas(Scope scope) {
        @Nullable ItemStack[] next = scope.inventory().currentState().clone();
        List<SlotDelta> deltas = scope.deltas();
        for (int i = 0; i < deltas.size(); i++) {
            SlotDelta delta = deltas.get(i);
            next[delta.slot()] = delta.rawAfter();
        }
        return next;
    }
}
