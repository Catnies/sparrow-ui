package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.InventoryDelta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public sealed interface TransactionResult {

    /**
     * 事务成功:
     * 所有参与库存的改动都已生效, post 事件也已派发完毕.
     *
     * @param changes 每个参与库存各一条变更记录, 按调用方声明的顺序排列
     */
    record Committed(@NotNull List<InventoryDelta> changes) implements TransactionResult {

        public Committed {
            changes = List.copyOf(changes);
        }
    }

    /**
     * 事务失败:
     * 在 pre 阶段被观察者取消.
     */
    enum Cancelled implements TransactionResult {
        INSTANCE
    }

    /**
     * 事务失败:
     * 提交时发现库存已被别的事务抢先改过, 本次事务整体放弃, 调用方可以基于最新快照重新规划后再试.
     */
    enum Conflicted implements TransactionResult {
        INSTANCE
    }

}
