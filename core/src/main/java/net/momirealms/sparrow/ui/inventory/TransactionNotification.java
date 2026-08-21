package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

// 一笔事务向一个 Inventory 发 Pre 与 Post 事件的那一份差事, 一笔事务改几个 Inventory 就有几份.
// 名单在事务开始时就定死, 所以 Pre 处理器新增的订阅或槽位修改不会把本轮 Pre 事件递归派发一遍.
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

    // 按订阅顺序跑完本轮 Pre 处理器, 带着上一个 Inventory 传下来的取消状态进, 带着本组跑完的取消状态出.
    boolean publishPre(boolean cancelled, @NotNull TransactionDraft draft, @Nullable InteractionDraft interaction) {
        SparrowInventory inventory = this.channel.inventory();
        for (int i = 0; i < this.preRecipients.size(); i++) {
            Observer<? super InventoryPreUpdateEvent> observer = this.preRecipients.get(i).observer();
            // 弱引用指向的观察者已经被回收时, 本轮直接跳过它.
            if (observer == null) continue;

            // 每个处理器都从最新草稿创建独立事件, 失败时不会污染其他处理器.
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
                // 处理器正常返回后, 先检查并接纳它修改的最终值.
                draft.accept(event.scopes());
                // 最终值成功接纳后才接受取消改动, 失败的处理器不能影响事务是否提交.
                cancelled = event.cancelled();
            } catch (Throwable exception) {
                SparrowUI.getInstance().handleException("Failed to handle Inventory pre-update", exception);
            } finally {
                // 处理器已经退出, 关闭编辑窗口, 阻止逃逸出去的事件引用继续修改事务.
                event.closeEditing();
            }
        }
        return cancelled;
    }

    // 开了串行派发就在提交临界区内领个票号; 没有 Post 接收者干脆不领, 领了不派发会让票号和放行对不上.
    void takePostTicket() {
        if (this.postRecipients.isEmpty()) return;
        this.postTicket = this.channel.takePostTicket();
    }

    // 在当前提交线程上把最终结果发给本轮 Post 订阅者; 领过票号的先等叫号, 同一 Inventory 的 Post 因此既不并发也不乱序.
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

    // 一个观察者炸了不影响其余的, 上报之后接着往下发.
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
