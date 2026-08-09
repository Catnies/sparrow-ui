package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一个 Inventory 的事务订阅器, 管理 PreUpdateEvent 和 PostUpdateEvent 订阅.
 * <p>每个 Inventory 至多拥有一个订阅器, 事务遇到它时按写集里属于本 Inventory 的那一组变更派发,
 * 因此当前 Inventory 的订阅者最多收到一次通知.
 * <p>事务开始时通过 {@link #prepare} 记录当时的订阅者名单.
 * PostUpdateEvent 会在整笔事务完成落地后由当前提交线程同步派发, 不同提交线程可以并发调用同一订阅者.
 */
final class InventoryUpdateChannel {
    private final SparrowInventory inventory; // 拥有本订阅器的 Inventory
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preSubscribers = new CopyOnWriteArrayList<>();   // PreUpdateEvent 订阅者
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postSubscribers = new CopyOnWriteArrayList<>(); // PostUpdateEvent 订阅者

    InventoryUpdateChannel(@NotNull SparrowInventory inventory) {
        this.inventory = inventory;
    }

    // 添加一个 PreUpdate 处理器.
    @NotNull
    Subscription subscribePre(@NotNull Observer<? super InventoryPreUpdateEvent> observer) {
        return this.subscribe(this.preSubscribers, observer);
    }

    // 添加一个 PostUpdate 处理器.
    @NotNull
    Subscription subscribePost(@NotNull Observer<? super InventoryPostUpdateEvent> observer) {
        return this.subscribe(this.postSubscribers, observer);
    }

    /**
     * 保存一个订阅者.
     *
     * @param subscribers PreUpdateEvent 或 PostUpdateEvent 的订阅者列表
     * @param observer 要添加的观察者
     * @param <E> 事件类型
     * @return 用于取消本次订阅的对象
     * @throws NullPointerException 当观察者为 {@code null} 时
     */
    @NotNull
    private <E> Subscription subscribe(
            @NotNull CopyOnWriteArrayList<InventoryUpdateSubscriber<E>> subscribers,
            @NotNull Observer<? super E> observer
    ) {
        InventoryUpdateSubscriber<E> subscriber = new InventoryUpdateSubscriber<>(subscribers, Objects.requireNonNull(observer, "observer"));
        subscribers.add(subscriber);
        return subscriber;
    }

    /**
     * 记录本笔事务开始时的订阅者名单, 并确定原始事务是否会向当前 Inventory 派发 PreUpdateEvent.
     * <p>PreUpdateEvent 处理器新增的槽位不会递归产生新的 PreUpdateEvent. PostUpdateEvent 订阅者始终先记录,
     * 等全部 PreUpdateEvent 完成后再按最终变更筛选, 使 PreUpdateEvent 新增的槽位不会漏掉提交后通知.
     *
     * @param reason 事务触发原因
     * @param scope 本笔事务写向当前 Inventory 的那一组变更
     * @param includePre 是否记录 PreUpdateEvent 订阅者
     * @return 本次要发送的事件; 不需要通知时返回 {@code null}
     */
    @Nullable
    TransactionNotification prepare(@NotNull UpdateReason reason, @NotNull TransactionScope scope, boolean includePre) {
        // 在调用任何 PreUpdateEvent 处理器之前, 一次性记住 PreUpdateEvent 和 PostUpdateEvent 的订阅者.
        List<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preRecipients = includePre ? List.copyOf(this.preSubscribers) : List.of();
        List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postRecipients = List.copyOf(this.postSubscribers);
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }

        // Pre 接收者只按原始事务里属于本 Inventory 的变更确定, 之后的编辑不会补派或递归派发 Pre.
        if (scope.slotChanges().isEmpty()) {
            preRecipients = List.of();
        }
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }
        return new TransactionNotification(this.inventory, reason, preRecipients, postRecipients);
    }
}
