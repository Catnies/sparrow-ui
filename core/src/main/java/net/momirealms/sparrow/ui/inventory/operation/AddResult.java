package net.momirealms.sparrow.ui.inventory.operation;

import net.momirealms.sparrow.ui.inventory.TransactionResult;
import org.jetbrains.annotations.NotNull;

/**
 * 放入类操作的结果.
 *
 * @param result 事务结果
 * @param remaining 未能放入的数量, 0 表示全部放入
 */
public record AddResult(@NotNull TransactionResult result, int remaining) {
}
