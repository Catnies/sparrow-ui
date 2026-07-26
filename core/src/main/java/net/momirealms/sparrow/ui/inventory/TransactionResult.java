package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一次库存事务的提交结果.
 * <p>取消与冲突都是正常控制流, 通过返回值表达而不抛异常; 异常只留给真正的
 * 编程错误(槽号越界, 非法参数等). 调用方对 {@link Conflicted} 自行决定重试,
 * 重试时 pre 处理器会再次运行.
 */
public sealed interface TransactionResult {

    /**
     * 事务已提交, 携带全部参与库存的变更视图.
     *
     * @param changes 按调用方声明顺序排列的各库存变更, 不可变列表
     */
    record Committed(@NotNull List<InventoryDelta> changes) implements TransactionResult {

        public Committed {
            changes = List.copyOf(changes);
        }
    }

    /**
     * pre 处理器取消了事务, 所有参与库存零变更.
     */
    enum Cancelled implements TransactionResult {
        INSTANCE
    }

    /**
     * 乐观校验失败: 某个参与库存在 plan 之后已被其他事务修改, 所有参与库存零变更.
     */
    enum Conflicted implements TransactionResult {
        INSTANCE
    }
}
