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

// 名单在事务开头就冻住. 这一轮里新加的订阅收不到本笔事件, 中途退订的也不会被漏掉一半.
final class TransactionNotification {
    private final InventoryUpdateChannel channel; // 事件往哪个 Inventory 的通道上发, 排队用的票号也向它领
    private final UpdateReason reason;            // 这笔事务为什么发生
    private final List<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preRecipients;   // 事务开头就已经订阅、并且确实看得到原始变化的那些 Pre 处理器
    private final List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postRecipients; // 事务开始时已经订阅的 Post 接收者

    private long postTicket = -1L; // 开了串行派发才有的票号, 在提交临界区里领, -1 表示这个 Inventory 不排队

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

    // 取消状态顺着 Inventory 和订阅顺序一路传下去, 每个处理器看到的初始值就是前一个留下的结论.
    boolean publishPre(boolean cancelled, @NotNull TransactionDraft draft, @Nullable InteractionDraft interaction) {
        SparrowInventory inventory = this.channel.inventory();
        for (int i = 0; i < this.preRecipients.size(); i++) {
            Observer<? super InventoryPreUpdateEvent> observer = this.preRecipients.get(i).observer();
            if (observer == null) continue;

            // 处理器正常返回才收下它改的写集, 中途抛异常就当它没改过.
            // 光标和掉落物那份草稿不一样, 整条 Pre 链共用同一个实例, 谁写了就当场生效.
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
                // 取消状态和刚通过校验的写集一起收下, 两者必须同进同退.
                cancelled = event.cancelled();
            } catch (Throwable exception) {
                SparrowUI.getInstance().handleException("Failed to handle Inventory pre-update", exception);
            } finally {
                event.closeEditing();
            }
        }
        return cancelled;
    }

    // 没人订阅就不领票. 领了不放会把后面所有 Post 永远堵在那里, 所以签发和放行必须一一对应.
    void takePostTicket() {
        if (this.postRecipients.isEmpty()) return;
        this.postTicket = this.channel.takePostTicket();
    }

    // 有票号就排队等自己那一轮, 派发完在 finally 里放行下一笔, 中途抛异常也得放.
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

    // 一个观察者抛异常不影响后面的, 报上去之后继续发完剩下的.
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
