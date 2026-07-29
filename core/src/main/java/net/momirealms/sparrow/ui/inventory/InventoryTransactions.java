package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.InventoryDelta;
import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.TransactionPostEvent;
import net.momirealms.sparrow.ui.inventory.event.TransactionPreEvent;
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
 * 库存事务引擎: 以整次事务为单位完成提交.
 * <p>管线为 plan(调用方在快照上规划) → pre(锁外, 可取消) → commit(锁内校验并
 * 交换快照) → post(锁外按提交顺序派发). 跨库存事务按锁序号全序加锁, 保证
 * 全成全败且不会死锁.
 */
final class InventoryTransactions {

    /**
     * 单个库存的事务参与范围.
     *
     * @param inventory 参与库存
     * @param planned plan 阶段读取的快照引用, commit 时以 identity 比对做乐观校验
     * @param deltas 该库存的槽位变更, 不可变列表
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
     * 提交一次事务; 取消与冲突通过返回值表达, 所有失败路径都保证零变更.
     *
     * @param reason 变更原因, 随事件原样传递
     * @param scopes 参与库存与各自的变更, 同一库存至多出现一次
     * @param bypassPre 为 {@code true} 时跳过全部 pre 观察者(post 仍然派发)
     */
    @NotNull
    static TransactionResult commit(@NotNull UpdateReason reason, @NotNull List<Scope> scopes, boolean bypassPre) {
        // 声明序列表负责事件载荷, 兑现"按调用方声明顺序"的公开契约;
        // 锁序列表只服务于加锁与提交, 两个顺序自此解耦
        List<Scope> declared = validateAndMerge(scopes);
        List<Scope> ordered = sortByLockOrder(declared);
        List<InventoryDelta> changes = changesOf(declared);

        // 不可访问的外部根在用户回调前拒绝, 不产生 pre/post 或任何镜像变更
        if (!writeAvailable(ordered)) {
            return TransactionResult.Unavailable.INSTANCE;
        }

        // pre 阶段: 锁外对每个参与根派发一次, 任一观察者取消则整个事务零变更结束
        if (!bypassPre) {
            TransactionPreEvent preEvent = new TransactionPreEvent(reason, changes);
            for (int i = 0; i < ordered.size(); i++) {
                ordered.get(i).inventory().publishPreUpdate(preEvent);
            }
            if (preEvent.cancelled()) {
                return TransactionResult.Cancelled.INSTANCE;
            }
        }

        int locked = 0;
        try {
            // 按全序逐把加锁, 消除跨库存事务的死锁可能
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

            // 先为全部库存构造新快照再统一交换, 保证越界等编程错误发生时零交换
            TransactionPostEvent postEvent = new TransactionPostEvent(reason, changes);
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

        // 提交后钩子先于 post 派发: 镜像型根在此把变更写回外部真相, 使 post 观察者
        // 重入写时外部状态已同步; 钩子异常隔离上报, 不影响已提交的事务结果
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

    private static boolean writeAvailable(List<Scope> scopes) {
        for (int i = 0; i < scopes.size(); i++) {
            if (!scopes.get(i).inventory().writeAvailable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验事务形状(非空, 槽号界内), 并合并指向同一根库存的多个范围 —— 视图场景
     * (共根的两个投影, 源与目标共根)会合法地产生它们. 合并要求各范围持有同一
     * planned 引用(同线程内两次读取之间无提交时必然成立), 且合并后同一槽位至多
     * 出现一次; 违反者是调用方缺陷, 立即失败.
     * <p>返回列表保持声明首现顺序, 事件载荷"按调用方声明顺序"的承诺以它为基准.
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

        // 按根库存归并, LinkedHashMap 保住声明首现顺序
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

        // 单根库存的物理映射在构造时保证一对一;只有多个根可能跨镜像写入同一个外部槽.
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

    // 加锁, 乐观校验与快照交换按锁序号全序进行, 与事件载荷的声明顺序解耦
    @NotNull
    private static List<Scope> sortByLockOrder(List<Scope> declared) {
        List<Scope> ordered = new ArrayList<>(declared);
        ordered.sort(Comparator.comparingLong(scope -> scope.inventory().lockOrder()));
        return ordered;
    }

    // 事件载荷按调用方声明顺序组装, 与加锁顺序无关
    @NotNull
    private static List<InventoryDelta> changesOf(List<Scope> scopes) {
        List<InventoryDelta> changes = new ArrayList<>(scopes.size());
        for (int i = 0; i < scopes.size(); i++) {
            Scope scope = scopes.get(i);
            changes.add(new InventoryDelta(scope.inventory(), scope.deltas()));
        }
        return List.copyOf(changes);
    }

    // 浅拷贝当前快照并落入各槽的 after 实例; 未变更槽与旧快照共享元素, 内部永不变异
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
