package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

// 保存一个 Inventory 在本笔事务开始时可见的 Pre 与 Post 接收者.
final class TransactionNotification {
    private final InventoryUpdateChannel channel; // 接收本组事件的 Inventory 的派发通道, 票号也向它领
    private final UpdateReason reason;            // 这笔事务为什么发生
    private final List<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preRecipients;   // 事务开始时已经订阅且能看到原始变化的 Pre 接收者
    private final List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postRecipients; // 事务开始时已经订阅的 Post 接收者

    private long postTicket = -1L; // 串行派发时在提交临界区内领到的票号, -1 表示不排队

    TransactionNotification(
            @NotNull InventoryUpdateChannel channel,
            @NotNull UpdateReason reason,
            @NotNull List<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preRecipients,
            @NotNull List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postRecipients
    ) {
        this.channel = channel;
        this.reason = reason;
        this.preRecipients = preRecipients;
        this.postRecipients = postRecipients;
    }

    // 取消状态按 Inventory 和订阅顺序传递.
    boolean publishPre(boolean cancelled, @NotNull TransactionDraft draft, @Nullable InteractionDraft interaction) {
        SparrowInventory inventory = this.channel.inventory();
        for (int i = 0; i < this.preRecipients.size(); i++) {
            Observer<? super InventoryPreUpdateEvent> observer = this.preRecipients.get(i).observer();
            if (observer == null) continue;

            // 写集修改在处理器正常返回后才接纳, 交互副作用草稿则由整条 Pre 链共享.
            InventoryPreUpdateEvent event = new InventoryPreUpdateEvent(
                    inventory,
                    this.reason,
                    draft.scopes(),
                    true,
                    draft::includeScope,
                    interaction
            );
            event.setCancelled(cancelled);
            try {
                observer.onUpdate(event);
                draft.accept(event.scopes());
                // 取消状态与通过校验的写集一起接纳.
                cancelled = event.cancelled();
            } catch (Throwable exception) {
                SparrowUI.getInstance().handleException("Failed to handle Inventory pre-update", exception);
            } finally {
                event.closeEditing();
            }
        }
        return cancelled;
    }

    // 无接收者时不领票, 保持签发与放行一一对应.
    void takePostTicket() {
        if (this.postRecipients.isEmpty()) return;
        this.postTicket = this.channel.takePostTicket();
    }

    // 有票号时按提交顺序等待, 并在 finally 中放行下一笔 Post.
    void publishPost(@NotNull List<TransactionScope> scopes, long version) {
        if (this.postRecipients.isEmpty()) return;
        if (this.postTicket < 0L) {
            this.dispatchPost(scopes, version);
            return;
        }
        this.channel.awaitPostTurn(this.postTicket);
        try {
            this.dispatchPost(scopes, version);
        } finally {
            this.channel.releasePostTurn();
        }
    }

    // 单个观察者失败后继续派发其余接收者.
    private void dispatchPost(@NotNull List<TransactionScope> scopes, long version) {
        InventoryPostUpdateEvent event = new InventoryPostUpdateEvent(this.channel.inventory(), this.reason, scopes, version);
        for (int i = 0; i < this.postRecipients.size(); i++) {
            Observer<? super InventoryPostUpdateEvent> observer = this.postRecipients.get(i).observer();
            if (observer != null) {
                try {
                    observer.onUpdate(event);
                } catch (Throwable exception) {
                    SparrowUI.getInstance().handleException("Failed to handle Inventory post-update", exception);
                }
            }
        }
    }
}
