package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.InventoryChange;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public sealed interface TransactionResult {

    /**
     * pre 阶段被观察者取消, 一格都没写.
     */
    enum Cancelled implements TransactionResult {
        INSTANCE
    }

    /**
     * 提交时发现规划基准已经被别的事务换掉, 整笔放弃重来, 一格都没写.
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
