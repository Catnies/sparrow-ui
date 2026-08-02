package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.event.RootInventoryChange;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一个 Inventory 的事务订阅器, 管理 PreUpdateEvent 和 PostUpdateEvent 订阅.
 * <p>有订阅者时, 它会登记到当前 Inventory 使用的所有 RootInventory 中.
 * 一笔事务即使同时修改了多个 RootInventory, 也只会找到同一个 InventoryUpdateChannel 一次,
 * 因此当前 Inventory 的订阅者最多收到一次通知.
 * <p>事务开始时通过 {@link #prepare} 记录当时的订阅者名单.
 * PostUpdateEvent 事件的排队和派发顺序由 {@link PostDeliveryQueue} 负责.
 */
final class InventoryUpdateChannel {
    private final SparrowInventory inventory; // 当前订阅使用其槽位坐标的 Inventory
    private final InventoryTopology topology; // 当前 Inventory 的槽位映射表
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preSubscribers = new CopyOnWriteArrayList<>();   // PreUpdateEvent 订阅者
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postSubscribers = new CopyOnWriteArrayList<>(); // PostUpdateEvent 订阅者
    private final PostDeliveryQueue postDeliveries = new PostDeliveryQueue(); // 当前 Inventory 的 PostUpdateEvent 顺序派发队列
    private final Object lifecycleLock = new Object(); // 防止订阅和取消订阅同时改变 RootInventory 中的登记

    private volatile boolean active; // 是否已经登记到所有 RootInventory

    /**
     * 创建一个尚未登记到 RootInventory 的 InventoryUpdateChannel.
     *
     * @param inventory 当前订阅的 Inventory
     * @param topology 当前 Inventory 的槽位映射
     */
    InventoryUpdateChannel(@NotNull SparrowInventory inventory, @NotNull InventoryTopology topology) {
        this.inventory = inventory;
        this.topology = topology;
    }

    /**
     * 添加一个 PreUpdate 处理器.
     * 第一个订阅者会让当前 InventoryUpdateChannel 登记到所有 RootInventory.
     *
     * @param observer 接收事件的观察者
     * @return 用于取消本次订阅的对象
     */
    @NotNull
    Subscription subscribePre(@NotNull Observer<? super InventoryPreUpdateEvent> observer) {
        return this.subscribe(this.preSubscribers, observer);
    }

    /**
     * 添加一个 PostUpdate 处理器
     * 第一个订阅者会让当前 InventoryUpdateChannel 登记到所有 RootInventory.
     *
     * @param observer 接收事件的观察者
     * @return 用于取消本次订阅的对象
     */
    @NotNull
    Subscription subscribePost(@NotNull Observer<? super InventoryPostUpdateEvent> observer) {
        return this.subscribe(this.postSubscribers, observer);
    }

    /**
     * 保存一个订阅者, 必要时把当前 InventoryUpdateChannel 登记到所有 RootInventory.
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
        InventoryUpdateSubscriber<E> subscriber = new InventoryUpdateSubscriber<>(this, subscribers, Objects.requireNonNull(observer, "observer"));
        synchronized (this.lifecycleLock) {
            // 先保存订阅者, 避免事务找到 InventoryUpdateChannel 时还看不到第一个订阅者.
            subscribers.add(subscriber);
            try {
                if (!this.active) {
                    this.install();
                }
            } catch (RuntimeException | Error throwable) {
                // 登记失败时撤销刚加入的订阅者.
                subscriber.invalidate();
                subscribers.remove(subscriber);
                throw throwable;
            }
        }
        return subscriber;
    }

    /**
     * 把当前 InventoryUpdateChannel 登记到所有 RootInventory, 使之后的事务能够找到它.
     * <p>如果中途失败, 已经完成的登记会被撤销.
     *
     * @throws RuntimeException 当登记或撤销登记失败时
     * @throws Error 当登记或撤销登记发生严重错误时
     */
    private void install() {
        int installed = 0;
        try {
            for (; installed < this.topology.rootCount(); installed++) {
                this.topology.rootAt(installed).addUpdateChannel(this);
            }
        } catch (RuntimeException | Error throwable) {
            // 从后往前撤销本次已经完成的登记.
            for (int i = installed - 1; i >= 0; i--) {
                int index = i;
                ThrowableUtils.captureUnchecked(throwable, () -> this.topology.rootAt(index).removeUpdateChannel(this));
            }
            throw throwable;
        }
        this.active = true;
    }

    /**
     * 移除一个订阅者.
     * 没有任何订阅者后, 同时从 RootInventory 中撤销当前 InventoryUpdateChannel.
     *
     * @param subscribers 该订阅者所在的列表
     * @param subscriber 要移除的订阅者
     * @param <E> 事件类型
     */
    <E> void removeSubscriber(
            @NotNull CopyOnWriteArrayList<InventoryUpdateSubscriber<E>> subscribers,
            @NotNull InventoryUpdateSubscriber<E> subscriber
    ) {
        synchronized (this.lifecycleLock) {
            subscribers.remove(subscriber);
            if (this.preSubscribers.isEmpty() && this.postSubscribers.isEmpty()) {
                this.uninstall();
            }
        }
    }

    /**
     * 从所有 RootInventory 中撤销当前 InventoryUpdateChannel, 使之后的事务不再处理它.
     * <p>先标记为停用, 并发事务即使暂时还能找到它也会直接跳过.
     */
    private void uninstall() {
        this.active = false;
        for (int i = 0; i < this.topology.rootCount(); i++) {
            this.topology.rootAt(i).removeUpdateChannel(this);
        }
    }

    /**
     * 判断当前 InventoryUpdateChannel 是否已经登记到所有 RootInventory.
     *
     * @return 是否可以处理新的事务
     */
    boolean isActive() {
        return this.active;
    }

    /**
     * 记录本笔事务开始时的订阅者名单, 并确定原始事务是否会向当前 Inventory 派发 PreUpdateEvent.
     * <p>PreUpdateEvent 处理器新增的槽位不会递归产生新的 PreUpdateEvent. PostUpdateEvent 订阅者始终先记录,
     * 等全部 PreUpdateEvent 完成后再按最终变更投影, 使 PreUpdateEvent 新增的可见槽位不会漏掉提交后通知.
     *
     * @param reason 事务触发原因
     * @param rootChanges 整笔事务的 RootInventory 变更组
     * @param includePre 是否记录 PreUpdateEvent 订阅者
     * @return 本次要发送的事件; 不需要通知时返回 {@code null}
     */
    @Nullable
    TransactionNotification prepare(
            @NotNull UpdateReason reason,
            @NotNull List<RootInventoryChange> rootChanges,
            boolean includePre
    ) {
        if (!this.active) {
            return null;
        }
        // 在调用任何 PreUpdateEvent 处理器之前, 一次性记住 PreUpdateEvent 和 PostUpdateEvent 的订阅者.
        List<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preRecipients = includePre ? List.copyOf(this.preSubscribers) : List.of();
        List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postRecipients = List.copyOf(this.postSubscribers);
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }

        // Pre 接收者只按原始事务投影确定, 之后的编辑不会补派或递归派发 Pre.
        List<SlotChange> deltas = this.topology.project(rootChanges);
        if (deltas.isEmpty()) {
            preRecipients = List.of();
        }
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }
        return new TransactionNotification(this.inventory, this.topology, this.postDeliveries, reason, preRecipients, postRecipients);
    }
}
