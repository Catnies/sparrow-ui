package net.momirealms.sparrow.ui.inventory.operation;

import net.momirealms.sparrow.ui.inventory.TransactionResult;
import org.jetbrains.annotations.NotNull;

/**
 * 移除类操作的结果.
 *
 * @param result 事务结果
 * @param removed 实际移除的数量
 */
public record RemoveResult(@NotNull TransactionResult result, int removed) {
}
