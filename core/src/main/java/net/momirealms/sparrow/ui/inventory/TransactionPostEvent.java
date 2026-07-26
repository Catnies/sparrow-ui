package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 事务提交后派发的纯通知事件, 在任何锁之外运行.
 * <p>对同一库存, 事件到达顺序与提交顺序一致; 派发线程是某个提交者线程,
 * 观察者不得假设特定线程. 本事件永不被抑制: 只要事务提交就一定派发.
 */
public final class TransactionPostEvent {
    private final UpdateReason reason;
    private final List<InventoryDelta> changes;

    TransactionPostEvent(@NotNull UpdateReason reason, @NotNull List<InventoryDelta> changes) {
        this.reason = reason;
        this.changes = changes;
    }

    @NotNull
    public UpdateReason reason() {
        return this.reason;
    }

    /**
     * 本次事务的完整变更视图, 按调用方声明顺序排列, 不可变.
     */
    @NotNull
    public List<InventoryDelta> changes() {
        return this.changes;
    }
}
