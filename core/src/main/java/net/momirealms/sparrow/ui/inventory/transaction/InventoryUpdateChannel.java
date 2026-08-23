package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

@ApiStatus.Internal
public final class InventoryUpdateChannel {
    private final SparrowInventory inventory;
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preSubscribers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postSubscribers = new CopyOnWriteArrayList<>();

    private volatile boolean serialPostDispatch;
    private final Object postGate = new Object(); // 只保护 Post 票号, 不保护 Inventory 状态
    private long nextPostTicket;                  // 在提交临界区签发
    private long servingPostTicket;               // 在 postGate 内推进

    public InventoryUpdateChannel(@NotNull SparrowInventory inventory) {
        this.inventory = inventory;
    }

    @NotNull
    public Subscription subscribePre(@NotNull Observer<? super InventoryPreUpdateEvent> observer) {
        return this.subscribe(this.preSubscribers, observer);
    }

    @NotNull
    public Subscription subscribePost(@NotNull Observer<? super InventoryPostUpdateEvent> observer) {
        return this.subscribe(this.postSubscribers, observer);
    }

    @NotNull
    private <E> Subscription subscribe(
            @NotNull CopyOnWriteArrayList<InventoryUpdateSubscriber<E>> subscribers,
            @NotNull Observer<? super E> observer
    ) {
        InventoryUpdateSubscriber<E> subscriber = new InventoryUpdateSubscriber<>(subscribers, Objects.requireNonNull(observer, "observer"));
        subscribers.add(subscriber);
        return subscriber;
    }

    // 事务开始时冻结接收者名单, 本轮新增订阅不会进入当前事务.
    @Nullable
    TransactionNotification prepare(@NotNull UpdateReason reason, @NotNull TransactionScope scope, boolean includePre) {
        List<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preRecipients = includePre ? List.copyOf(this.preSubscribers) : List.of();
        List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postRecipients = List.copyOf(this.postSubscribers);
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }

        // 空原始写集不派 Pre, Post 仍可覆盖 Pre 后新增的变更.
        if (scope.slotChanges().isEmpty()) {
            preRecipients = List.of();
        }
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }
        return new TransactionNotification(this, reason, preRecipients, postRecipients);
    }

    // 票号在提交临界区签发, 顺序与状态提交一致.
    long takePostTicket() {
        if (!this.serialPostDispatch) return -1L;
        return this.nextPostTicket++;
    }

    // 等待期间延迟处理中断, 当前票完成后恢复中断状态.
    void awaitPostTurn(long ticket) {
        boolean interrupted = false;
        synchronized (this.postGate) {
            while (this.servingPostTicket != ticket) {
                try {
                    this.postGate.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // <strong>必须在 awaitPostTurn 后的 finally 中调用</strong>, 否则后续 Post 会永久等待.
    void releasePostTurn() {
        synchronized (this.postGate) {
            this.servingPostTicket++;
            this.postGate.notifyAll();
        }
    }

    public boolean serialPostDispatch() {
        return this.serialPostDispatch;
    }

    public void serialPostDispatch(boolean serialPostDispatch) {
        this.serialPostDispatch = serialPostDispatch;
    }

    @NotNull
    SparrowInventory inventory() {
        return this.inventory;
    }
}
