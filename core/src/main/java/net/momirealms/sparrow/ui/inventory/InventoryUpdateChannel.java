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

// 一个 Inventory 的事务订阅器, 每个 Inventory 至多一个. 事务只按写集里属于本 Inventory 的那一组变更派发.
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
        // Post 名单则先留着, 等 Pre 全跑完再按最终变更筛, 免得漏掉 Pre 新增槽位的提交后通知
        if (scope.slotChanges().isEmpty()) {
            preRecipients = List.of();
        }
        if (preRecipients.isEmpty() && postRecipients.isEmpty()) {
            return null;
        }
        return new TransactionNotification(this.inventory, reason, preRecipients, postRecipients);
    }
}
