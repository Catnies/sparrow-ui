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

// 一个 Inventory 的事务订阅器, 每个 Inventory 至多一个. 事务只按写集里属于本 Inventory 的那一组变更派发.
// Post 的串行叫号也归它, 那个顺序是"每个 Inventory 一条"的事, 而这里正是每 Inventory 一份的派发对象.
@ApiStatus.Internal
public final class InventoryUpdateChannel {
    private final SparrowInventory inventory; // 拥有本订阅器的 Inventory
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preSubscribers = new CopyOnWriteArrayList<>();   // PreUpdateEvent 订阅者
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postSubscribers = new CopyOnWriteArrayList<>(); // PostUpdateEvent 订阅者

    private volatile boolean serialPostDispatch;  // 开启后本 Inventory 的 Post 严格按提交顺序串行派发, 后到的提交线程阻塞等待
    private final Object postGate = new Object(); // 只保护下面两个票号, 不保护任何内容状态
    private long nextPostTicket;                  // 下一个待签发的票号, 只在提交临界区内自增
    private long servingPostTicket;               // 正在派发的票号, 只在 postGate 内自增

    public InventoryUpdateChannel(@NotNull SparrowInventory inventory) {
        this.inventory = inventory;
    }

    // 添加一个 PreUpdate 处理器.
    @NotNull
    public Subscription subscribePre(@NotNull Observer<? super InventoryPreUpdateEvent> observer) {
        return this.subscribe(this.preSubscribers, observer);
    }

    // 添加一个 PostUpdate 处理器.
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

    // 事务开始时点一次名, 顺便决定这笔事务要不要给本 Inventory 派 Pre, 谁都不用通知时返回 null.
    @Nullable
    TransactionNotification prepare(@NotNull UpdateReason reason, @NotNull TransactionScope scope, boolean includePre) {
        // 赶在任何 Pre 处理器跑起来之前把两份名单一起抄下来, 处理器新增的订阅就影响不到本轮了
        List<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preRecipients = includePre ? List.copyOf(this.preSubscribers) : List.of();
        List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postRecipients = List.copyOf(this.postSubscribers);
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }

        // Pre 接收者只认原始事务里属于本 Inventory 的变更, 之后的编辑既不补派也不递归派 Pre;
        // Post 名单则先留着, 等 Pre 全跑完再按最终变更筛, Pre 新增的槽位也能拿到提交后通知
        if (scope.slotChanges().isEmpty()) {
            preRecipients = List.of();
        }
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }
        return new TransactionNotification(this, reason, preRecipients, postRecipients);
    }

    // 在提交临界区里领一个票号, 领到的先后就是提交的先后; 没开串行派发时返回 -1.
    long takePostTicket() {
        // 能开串行派发的只有 VirtualInventory, 它提交时必然持着自己的写锁, 所以 nextPostTicket 自增不必再加同步
        if (!this.serialPostDispatch) return -1L;
        return this.nextPostTicket++;
    }

    // 阻塞到自己的票号被叫到. 这里故意不响应中断, 排在后面的票要等这一次走完才轮得到.
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

    // 叫下一个票号. 必须与 awaitPostTurn 在同一个 finally 里配对, 漏掉一次, 本 Inventory 之后的提交线程就全卡死了.
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
