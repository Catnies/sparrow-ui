package net.momirealms.sparrow.ui.inventory.operation;

import net.momirealms.sparrow.ui.inventory.TransactionResult;
import org.jetbrains.annotations.NotNull;

/**
 * 放入类操作的结果.
 * <p>事务的全成全败针对 delta 集; 放入操作本身允许部分满足, 未能放入的数量
 * 由 {@link #remaining()} 报告. 事务被取消、冲突或不可用时零变更,
 * remaining 等于全部输入量.
 *
 * @param result 事务结果
 * @param remaining 未能放入的数量, 0 表示全部放入
 */
public record AddResult(@NotNull TransactionResult result, int remaining) {
}
