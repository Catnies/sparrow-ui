package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

/**
 * 收集类操作的结果.
 * <p>事务被取消或冲突时库存零变更, collected 为 0.
 *
 * @param result 事务结果
 * @param collected 实际从库存收集到的数量
 */
public record CollectResult(@NotNull TransactionResult result, int collected) {
}
