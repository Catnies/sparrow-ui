package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个可以单独取消的 Inventory 更新订阅.
 * <p>取消时先清空观察者, 再从订阅列表中移除. 即使某笔事务已经记住这个订阅,
 * 真正发送事件时也会发现它已经关闭并跳过回调.
 *
 * @param <E> 事件类型
 */
final class InventoryUpdateSubscriber<E> implements Subscription {
    private final InventoryUpdateChannel owner;                                   // 所属 InventoryUpdateChannel
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<E>> subscribers; // 当前订阅所在的列表
    private final AtomicReference<Observer<? super E>> observer;                  // null 表示已经取消订阅

    InventoryUpdateSubscriber(
            @NotNull InventoryUpdateChannel owner,
            @NotNull CopyOnWriteArrayList<InventoryUpdateSubscriber<E>> subscribers,
            @NotNull Observer<? super E> observer
    ) {
        this.owner = owner;
        this.subscribers = subscribers;
        this.observer = new AtomicReference<>(observer);
    }

    // 返回当前观察者, 订阅已经取消时为 {@code null}
    @Nullable
    Observer<? super E> observer() {
        return this.observer.get();
    }

    // 清空观察者, 让已经记住本订阅的事务跳过回调.
    void invalidate() {
        this.observer.set(null);
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
