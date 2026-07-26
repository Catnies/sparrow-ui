package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

/**
 * 移除类操作的结果.
 * <p>事务被取消或冲突时库存零变更, removed 为 0.
 *
 * @param result 事务结果
 * @param removed 实际移除的数量
 */
public record RemoveResult(@NotNull TransactionResult result, int removed) {
}
