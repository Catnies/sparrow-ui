package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.InventoryChange;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Inventory 事务的提交结果. Cancelled 与 Conflicted 都不会写入任何参与者.
 */
public sealed interface TransactionResult {

    /**
     * 玩家侧冻结检查或 pre 阶段取消了事务, 没有槽位被写入.
     */
    enum Cancelled implements TransactionResult {
        INSTANCE
    }

    /**
     * 提交条件或规划基准已经失效, 没有槽位被写入.
     */
    enum Conflicted implements TransactionResult {
        INSTANCE
    }

    /**
     * 参与的每个 Inventory 都已经写入成功.
     *
     * @param rootChanges 每个参与的 Inventory 各一个变更组, 按调用方声明的顺序排列
     */
    record Committed(@NotNull List<InventoryChange> rootChanges) implements TransactionResult {

        public Committed {
            rootChanges = List.copyOf(rootChanges);
        }
    }
}
