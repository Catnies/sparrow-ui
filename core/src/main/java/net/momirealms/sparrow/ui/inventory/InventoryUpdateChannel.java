package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个 Inventory 的事务订阅器, 管理 PreUpdateEvent 和 PostUpdateEvent 订阅.
 * <p>有订阅者时, 它会登记到当前 Inventory 使用的所有 RootInventory 中.
 * 一笔事务即使同时修改了多个 RootInventory, 也只会找到同一个 InventoryUpdateChannel 一次,
 * 因此当前 Inventory 的订阅者最多收到一次通知.
 * <p>事务开始时会记录当时的订阅者名单. PostUpdateEvent 事件按照事务提交顺序排队,
 * 并在所有 RootInventory 都完成提交后处理后再通知订阅者.
 */
final class InventoryUpdateChannel {
    private final InventoryTopology topology; // 当前 Inventory 的槽位映射表
    private final CopyOnWriteArrayList<Subscriber<InventoryPreUpdateEvent>> preSubscribers = new CopyOnWriteArrayList<>();   // PreUpdateEvent 订阅者
    private final CopyOnWriteArrayList<Subscriber<InventoryPostUpdateEvent>> postSubscribers = new CopyOnWriteArrayList<>(); // PostUpdateEvent 订阅者
    private final ConcurrentLinkedQueue<PostDelivery> pendingPostDeliveries = new ConcurrentLinkedQueue<>(); // 等待发送的 PostUpdateEvent
    private final AtomicBoolean drainingPostDeliveries = new AtomicBoolean(); // 是否已有线程正在发送 PostUpdateEvent
    private final Object lifecycleLock = new Object(); // 防止订阅和取消订阅同时改变 RootInventory 中的登记

    private volatile boolean active; // 是否已经登记到所有 RootInventory

    /**
     * 创建一个尚未登记到 RootInventory 的 InventoryUpdateChannel.
     *
     * @param topology 当前 Inventory 的槽位映射
     */
    InventoryUpdateChannel(@NotNull InventoryTopology topology) {
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
            @NotNull CopyOnWriteArrayList<Subscriber<E>> subscribers,
            @NotNull Observer<? super E> observer
    ) {
        Subscriber<E> subscriber = new Subscriber<>(this, subscribers, Objects.requireNonNull(observer, "observer"));
        synchronized (this.lifecycleLock) {
            // 先保存订阅者, 避免事务找到 InventoryUpdateChannel 时还看不到第一个订阅者.
            subscribers.add(subscriber);
            try {
                if (!this.active) {
                    this.install();
                }
            } catch (RuntimeException | Error throwable) {
                // 登记失败时撤销刚加入的订阅者.
                subscriber.observer.set(null);
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
    private <E> void removeSubscriber(
            @NotNull CopyOnWriteArrayList<Subscriber<E>> subscribers,
            @NotNull Subscriber<E> subscriber
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
     * 记录本笔事务开始时的订阅者名单, 并把 RootInventory 槽位变更投影为当前 Inventory 槽位变更.
     * <p>PreUpdateEvent 和 PostUpdateEvent 订阅者会同时记录, 所以 PreUpdateEvent 处理器中新加的订阅者要从下一笔事务开始接收事件.
     * 当前 Inventory 没有可见槽位变更时不创建事件.
     *
     * @param reason 事务触发原因
     * @param rootChanges 整笔事务的 RootInventory 变更组
     * @param includePre 是否记录 PreUpdateEvent 订阅者
     * @return 本次要发送的事件; 不需要通知时返回 {@code null}
     */
    @Nullable
    Prepared prepare(
            @NotNull UpdateReason reason,
            @NotNull List<RootInventoryChange> rootChanges,
            boolean includePre
    ) {
        if (!this.active) {
            return null;
        }
        // 在调用任何 PreUpdateEvent 处理器之前, 一次性记住 PreUpdateEvent 和 PostUpdateEvent 的订阅者.
        List<Subscriber<InventoryPreUpdateEvent>> preRecipients = includePre ? List.copyOf(this.preSubscribers) : List.of();
        List<Subscriber<InventoryPostUpdateEvent>> postRecipients = List.copyOf(this.postSubscribers);
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }

        // 每个 Inventory 只投影一次; ObscuredInventory 中只有被遮槽位发生变更时会得到空结果.
        List<SlotChange> deltas = this.topology.project(rootChanges);
        if (deltas.isEmpty()) {
            return null;
        }

        // 没有对应订阅者时, 不创建该事件.
        InventoryPreUpdateEvent preEvent = preRecipients.isEmpty()
                ? null
                : new InventoryPreUpdateEvent(reason, deltas, rootChanges);
        PostDelivery post = postRecipients.isEmpty()
                ? null
                : new PostDelivery(postRecipients, new InventoryPostUpdateEvent(reason, deltas, rootChanges));
        return new Prepared(preRecipients, preEvent, post);
    }

    /**
     * 按事务提交顺序派发所有已经可以派发的 PostUpdateEvent 事件.
     * <p>排在前面的事件尚未完成时, 后面的事件必须继续等待. 发送线程退出前会再次检查队列,
     * 避免恰好在退出过程中准备好的事件一直留在队列中.
     */
    private void drainPostDeliveries() {
        // 同一时间只允许一个线程派发队列中的事件.
        while (this.drainingPostDeliveries.compareAndSet(false, true)) {
            try {
                // 只从队首开始派发, 前一个事件尚不允许派发时不能跳到后面.
                while (true) {
                    PostDelivery next = this.pendingPostDeliveries.peek();
                    if (next == null || !next.ready) {
                        break;
                    }
                    PostDelivery delivery = this.pendingPostDeliveries.poll();
                    if (delivery != null) {
                        publish(delivery.recipients, delivery.event, "Failed to handle Inventory post-update");
                    }
                }
            } finally {
                this.drainingPostDeliveries.set(false);
            }

            // 释放派发权后再看一次, 处理刚刚允许派发但没有被其他线程接手的事件.
            PostDelivery next = this.pendingPostDeliveries.peek();
            if (next == null || !next.ready) {
                break;
            }
        }
    }

    /**
     * 把事件派发给本笔事务开始时记录的订阅者.
     * <p>已经取消订阅的观察者会被跳过. 单个观察者抛出异常时只会上报错误,
     * 不会影响其他观察者继续接收事件.
     *
     * @param recipients 本笔事务需要通知的订阅者
     * @param event 要派发的事件
     * @param failureMessage 上报异常时使用的消息
     * @param <E> 事件类型
     */
    private static <E> void publish(
            @NotNull List<Subscriber<E>> recipients,
            @NotNull E event,
            @NotNull String failureMessage
    ) {
        for (int i = 0; i < recipients.size(); i++) {
            Observer<? super E> observer = recipients.get(i).observer.get();
            if (observer == null) {
                continue;
            }
            try {
                observer.onUpdate(event);
            } catch (Throwable exception) {
                SparrowUI.getInstance().handleException(failureMessage, exception);
            }
        }
    }

    /**
     * 保存当前 Inventory 在一笔事务中需要派发的事件.
     * <p>PreUpdateEvent 事件可以立即派发. PostUpdateEvent 事件先进入队列,
     * 等所有 RootInventory 完成提交后处理后才能派发.
     */
    final class Prepared {
        private final List<Subscriber<InventoryPreUpdateEvent>> preRecipients; // 本笔事务需要通知的 PreUpdateEvent 订阅者
        @Nullable private final InventoryPreUpdateEvent preEvent;              // 没有 PreUpdateEvent 订阅者时为 null
        @Nullable private final PostDelivery post;                             // 没有 PostUpdateEvent 订阅者时为 null

        /**
         * 保存本笔事务的 PreUpdateEvent 和 PostUpdateEvent 事件.
         *
         * @param preRecipients 需要通知的 PreUpdateEvent 订阅者
         * @param preEvent  PreUpdateEvent 事件, 或 {@code null}
         * @param post  PostUpdateEvent 事件, 或 {@code null}
         */
        private Prepared(
                @NotNull List<Subscriber<InventoryPreUpdateEvent>> preRecipients,
                @Nullable InventoryPreUpdateEvent preEvent,
                @Nullable PostDelivery post
        ) {
            this.preRecipients = preRecipients;
            this.preEvent = preEvent;
            this.post = post;
        }

        /**
         * 派发 PreUpdateEvent 事件, 并返回所有处理器执行后的取消状态.
         *
         * @param cancelled 前一个 Inventory 事件留下的取消状态
         * @return 当前事件处理完成后的取消状态
         */
        boolean publishPre(boolean cancelled) {
            if (this.preEvent != null) {
                this.preEvent.setCancelled(cancelled);
                InventoryUpdateChannel.publish(this.preRecipients, this.preEvent, "Failed to handle Inventory pre-update");
                return this.preEvent.cancelled();
            }
            return cancelled;
        }

        /**
         * 在事务提交时把 PostUpdateEvent 事件放入队列, 保住事务之间的通知顺序.
         * <p>事件此时还不能派发, 后面的事务只能排在它后面等待.
         */
        void reservePost() {
            if (this.post != null) {
                InventoryUpdateChannel.this.pendingPostDeliveries.add(this.post);
            }
        }

        /**
         * 标记所有 RootInventory 已经完成提交后处理, 允许派发当前事件.
         */
        void markPostReady() {
            if (this.post != null) {
                this.post.ready = true;
            }
        }

        /**
         * 从队首连续派发已经允许派发的 PostUpdateEvent 事件.
         */
        void drainPost() {
            if (this.post != null) {
                InventoryUpdateChannel.this.drainPostDeliveries();
            }
        }
    }

    /**
     * 队列中的一个 PostUpdateEvent 事件.
     * <p>加入队列时还不能派发, 所有 RootInventory 完成提交后处理后才会允许派发.
     */
    private static final class PostDelivery {
        private final List<Subscriber<InventoryPostUpdateEvent>> recipients; // 本笔事务需要通知的 PostUpdateEvent 订阅者
        private final InventoryPostUpdateEvent event;                        // 槽位已经转换为当前 Inventory 坐标的事件
        private volatile boolean ready;                                      // 是否已经允许派发

        /**
         * 创建一个尚不允许派发的 PostUpdateEvent 事件.
         *
         * @param recipients 需要通知的 PostUpdateEvent 订阅者
         * @param event 要派发的事件
         */
        private PostDelivery(
                @NotNull List<Subscriber<InventoryPostUpdateEvent>> recipients,
                @NotNull InventoryPostUpdateEvent event
        ) {
            this.recipients = recipients;
            this.event = event;
        }
    }

    /**
     * 一个可以单独取消的订阅.
     * <p>取消时先清空观察者, 再从订阅列表中移除. 即使某笔事务已经记住这个订阅,
     * 真正发送事件时也会发现它已经关闭并跳过回调.
     *
     * @param <E> 事件类型
     */
    private static final class Subscriber<E> implements Subscription {
        private final InventoryUpdateChannel owner;                    // 所属 InventoryUpdateChannel
        private final CopyOnWriteArrayList<Subscriber<E>> subscribers; // 当前订阅所在的列表
        private final AtomicReference<Observer<? super E>> observer;   // null 表示已经取消订阅

        /**
         * 创建一个新的订阅.
         *
         * @param owner 所属 InventoryUpdateChannel
         * @param subscribers 订阅要加入的列表
         * @param observer 接收事件的观察者
         */
        private Subscriber(
                @NotNull InventoryUpdateChannel owner,
                @NotNull CopyOnWriteArrayList<Subscriber<E>> subscribers,
                @NotNull Observer<? super E> observer
        ) {
            this.owner = owner;
            this.subscribers = subscribers;
            this.observer = new AtomicReference<>(observer);
        }

        @Override
        public boolean isClosed() {
            return this.observer.get() == null;
        }

        @Override
        public void close() {
            // 先让正在处理的事务看到订阅已经关闭, 再从列表中移除.
            if (this.observer.getAndSet(null) != null) {
                this.owner.removeSubscriber(this.subscribers, this);
            }
        }
    }
}
