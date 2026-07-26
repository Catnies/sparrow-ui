package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 事务提交前派发的事件, 在任何锁之外运行.
 * <p>载荷是本次事务的完整变更视图(含全部参与库存). 调用 {@link #cancel()} 取消的
 * 是整个事务: 所有参与库存零变更, 不存在跳过单个槽位的中间态. 取消不可撤销.
 * <p>同一事务被调用方重试时, 本事件会再次派发.
 */
public final class TransactionPreEvent {
    private final UpdateReason reason;
    private final List<InventoryDelta> changes;
    private boolean cancelled; // 只在提交者线程的顺序派发中翻转, 无并发访问

    TransactionPreEvent(@NotNull UpdateReason reason, @NotNull List<InventoryDelta> changes) {
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

    /**
     * 取消整个事务. 调用后事务不会提交, 也不会派发 post 事件.
     */
    public void cancel() {
        this.cancelled = true;
    }

    public boolean cancelled() {
        return this.cancelled;
    }
}
