package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

// 一个可以单独取消的 Inventory 更新订阅, 取消后已经被事务记下的这一份也会被跳过.
final class InventoryUpdateSubscriber<E> implements Subscription {
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<E>> subscribers; // 当前订阅所在的列表
    private final AtomicReference<Observer<? super E>> observer;                  // null 表示已经取消订阅

    InventoryUpdateSubscriber(
            @NotNull CopyOnWriteArrayList<InventoryUpdateSubscriber<E>> subscribers,
            @NotNull Observer<? super E> observer
    ) {
        this.subscribers = subscribers;
        this.observer = new AtomicReference<>(observer);
    }

    @Nullable
    Observer<? super E> observer() {
        return this.observer.get();
    }

    @Override
    public boolean isClosed() {
        return this.observer.get() == null;
    }

    @Override
    public void close() {
        // 先清空观察者再摘出列表, 让已经拿到这一份的事务立刻看到关闭状态并跳过回调.
        if (this.observer.getAndSet(null) != null) {
            this.subscribers.remove(this);
        }
    }
}
