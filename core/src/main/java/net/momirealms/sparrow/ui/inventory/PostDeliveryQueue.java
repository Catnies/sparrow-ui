package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个 Inventory 的 PostUpdateEvent 顺序派发队列.
 * <p>事件在事务提交时按提交顺序入队, 但要等所有 RootInventory 都完成提交后处理才允许派发,
 * 保证后提交的事务不会先发出通知.
 */
final class PostDeliveryQueue {
    private final ConcurrentLinkedQueue<PostDelivery> pending = new ConcurrentLinkedQueue<>(); // 等待发送的 PostUpdateEvent
    private final AtomicBoolean draining = new AtomicBoolean();                                // 是否已有线程正在发送

    /**
     * 在事务提交时把事件放入队列, 保住事务之间的通知顺序.
     * <p>事件此时还不能派发, 后面的事务只能排在它后面等待.
     *
     * @param delivery 要排队的 PostUpdateEvent 事件
     */
    void reserve(@NotNull PostDelivery delivery) {
        this.pending.add(delivery);
    }

    /**
     * 按事务提交顺序派发所有已经可以派发的 PostUpdateEvent 事件.
     * <p>排在前面的事件尚未完成时, 后面的事件必须继续等待. 发送线程退出前会再次检查队列,
     * 避免恰好在退出过程中准备好的事件一直留在队列中.
     */
    void drain() {
        // 同一时间只允许一个线程派发队列中的事件.
        while (this.draining.compareAndSet(false, true)) {
            try {
                // 只从队首开始派发, 前一个事件尚不允许派发时不能跳到后面.
                while (true) {
                    PostDelivery next = this.pending.peek();
                    if (next == null || !next.ready) {
                        break;
                    }
                    PostDelivery delivery = this.pending.poll();
                    if (delivery != null) {
                        deliver(delivery);
                    }
                }
            } finally {
                this.draining.set(false);
            }

            // 释放派发权后再看一次, 处理刚刚允许派发但没有被其他线程接手的事件.
            PostDelivery next = this.pending.peek();
            if (next == null || !next.ready) {
                break;
            }
        }
    }

    /**
     * 把事件派发给本笔事务开始时记录的订阅者.
     * <p>已经取消订阅的观察者会被跳过.
     *
     * @param delivery 要派发的 PostUpdateEvent 事件
     */
    private static void deliver(@NotNull PostDelivery delivery) {
        List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> recipients = delivery.recipients;
        for (int i = 0; i < recipients.size(); i++) {
            Observer<? super InventoryPostUpdateEvent> observer = recipients.get(i).observer();
            if (observer == null) {
                continue;
            }
            try {
                observer.onUpdate(delivery.event);
            } catch (Throwable exception) {
                SparrowUI.getInstance().handleException("Failed to handle Inventory post-update", exception);
            }
        }
    }

    /**
     * 队列中的一个 PostUpdateEvent 事件.
     * <p>加入队列时还不能派发, 所有 RootInventory 完成提交后处理后才会允许派发.
     */
    static final class PostDelivery {
        private final List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> recipients; // 本笔事务需要通知的 PostUpdateEvent 订阅者
        private final InventoryPostUpdateEvent event;                                       // 槽位已经转换为当前 Inventory 坐标的事件
        private volatile boolean ready;                                                     // 是否已经允许派发

        /**
         * 创建一个尚不允许派发的 PostUpdateEvent 事件.
         *
         * @param recipients 需要通知的 PostUpdateEvent 订阅者
         * @param event 要派发的事件
         */
        PostDelivery(@NotNull List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> recipients, @NotNull InventoryPostUpdateEvent event) {
            this.recipients = recipients;
            this.event = event;
        }

        /**
         * 标记所有 RootInventory 已经完成提交后处理, 允许派发当前事件.
         */
        void markReady() {
            this.ready = true;
        }
    }
}
