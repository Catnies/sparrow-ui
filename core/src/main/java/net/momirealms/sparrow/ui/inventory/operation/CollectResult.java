package net.momirealms.sparrow.ui.inventory.operation;

import net.momirealms.sparrow.ui.inventory.TransactionResult;
import org.jetbrains.annotations.NotNull;

/**
 * 收集类操作的结果.
 *
 * @param result 事务结果
 * @param collected 实际从库存收集到的数量
 */
public record CollectResult(@NotNull TransactionResult result, int collected) {
}
