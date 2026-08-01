package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.RootInventoryChange;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public sealed interface TransactionResult {

    /**
     * 事务成功:
     * 所有参与的 RootInventory 的改动都已生效, post 事件也已派发完毕.
     *
     * @param rootChanges 每个参与的 RootInventory 各一个变更组, 按调用方声明的顺序排列
     */
    record Committed(@NotNull List<RootInventoryChange> rootChanges) implements TransactionResult {

        public Committed {
            rootChanges = List.copyOf(rootChanges);
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
     * 提交时发现 RootInventory 的规划基准状态引用已经变化, 本次事务整体放弃.
     */
    enum Conflicted implements TransactionResult {
        INSTANCE
    }

}
