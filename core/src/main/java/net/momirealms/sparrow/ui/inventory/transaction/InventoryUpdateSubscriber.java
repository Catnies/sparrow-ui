package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

// 关闭后即使已被事务快照捕获, 回调也会被跳过.
final class InventoryUpdateSubscriber<E> implements Subscription {
    private final CopyOnWriteArrayList<InventoryUpdateSubscriber<E>> subscribers;
    private final AtomicReference<Observer<? super E>> observer;

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
        // 先清空观察者, 让已有事务快照立即看到关闭状态.
        if (this.observer.getAndSet(null) != null) {
            this.subscribers.remove(this);
        }
    }
}
