package net.momirealms.sparrow.ui.inventory.event;

import org.jetbrains.annotations.ApiStatus;
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
    private volatile boolean cancelled; // volatile 兜底跨线程误用时的可见性, 正常路径只在派发线程翻转

    /**
     * 由事务引擎在 pre 阶段构造.
     * <p>公开只为跨包协作, 不属于稳定 API.
     */
    @ApiStatus.Internal
    public TransactionPreEvent(@NotNull UpdateReason reason, @NotNull List<InventoryDelta> changes) {
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
     * <p>只应在 pre 观察者的派发调用内同步调用; 把事件泄漏到其他线程再异步取消
     * 没有生效保证 —— 事务可能已经越过取消检查点.
     */
    public void cancel() {
        this.cancelled = true;
    }

    public boolean cancelled() {
        return this.cancelled;
    }
}
