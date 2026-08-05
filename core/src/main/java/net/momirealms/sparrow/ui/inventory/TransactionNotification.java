package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 负责一笔事务向某个 Inventory 发送 Pre 和 Post 事件.
 * <p>一笔事务可能同时修改多个 Inventory, 每个需要接收事件的 Inventory 都会有一个
 * {@code TransactionNotification}. 它在事务开始时记住本轮订阅者, 因此 Pre 处理器新增的订阅或槽位修改
 * 不会让本轮 Pre 事件递归派发.
 * <p>Pre 事件会在提交前立即逐个调用. Post 事件则根据所有 Pre 修改后的最终结果创建,
 * 等内容真正写入且各个 Inventory 都完成提交后的工作, 再按事务提交顺序发送.
 */
final class TransactionNotification {
    private final SparrowInventory inventory;       // 接收本组事件的 Inventory
    private final PostDeliveryQueue postDeliveries; // 保证当前 Inventory 的 Post 事件按提交顺序发送
    private final UpdateReason reason;              // 这笔事务为什么发生
    private final List<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preRecipients;   // 事务开始时已经订阅且能看到原始变化的 Pre 接收者
    private final List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postRecipients; // 事务开始时已经订阅的 Post 接收者

    @Nullable private PostDeliveryQueue.PostDelivery post; // 根据最终结果准备好的 Post 事件, 不需要发送时为 null

    /**
     * 记录某个 Inventory 在本次事务中需要通知的人和发送事件所需的信息.
     *
     * @param inventory 接收事件的 Inventory
     * @param postDeliveries 当前 Inventory 的 Post 事件发送队列
     * @param reason 这笔事务的触发原因
     * @param preRecipients 本轮需要调用的 Pre 订阅者
     * @param postRecipients 本轮可能需要调用的 Post 订阅者
     */
    TransactionNotification(
            @NotNull SparrowInventory inventory,
            @NotNull PostDeliveryQueue postDeliveries,
            @NotNull UpdateReason reason,
            @NotNull List<InventoryUpdateSubscriber<InventoryPreUpdateEvent>> preRecipients,
            @NotNull List<InventoryUpdateSubscriber<InventoryPostUpdateEvent>> postRecipients
    ) {
        this.inventory = inventory;
        this.postDeliveries = postDeliveries;
        this.reason = reason;
        this.preRecipients = preRecipients;
        this.postRecipients = postRecipients;
    }

    /**
     * 按订阅顺序调用本轮所有 Pre 处理器.
     * 回调结束后, 该事件也不能再修改事务.
     *
     * @param cancelled 上一个 Inventory 通知结束后留下的取消状态
     * @param draft 目前已经被正常处理器接受的最终提交内容
     * @param interaction 触发本笔事务的交互副作用草稿, 非玩家交互为 {@code null}
     * @return 本组 Pre 处理器全部执行后留下的取消状态
     */
    boolean publishPre(boolean cancelled, @NotNull TransactionDraft draft, @Nullable InteractionDraft interaction) {
        for (int i = 0; i < this.preRecipients.size(); i++) {
            Observer<? super InventoryPreUpdateEvent> observer = this.preRecipients.get(i).observer();
            // 弱引用指向的观察者已经被回收时, 本轮直接跳过它.
            if (observer == null) continue;

            // 每个处理器都从最新草稿创建独立事件, 失败时不会污染其他处理器.
            InventoryPreUpdateEvent event = new InventoryPreUpdateEvent(
                    this.inventory,
                    this.reason,
                    draft.scopes(),
                    true,
                    draft::baselineOf,
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

    /**
     * 根据所有 Pre 处理器修改后的最终结果准备 Post 事件.
     * <p>只有当前 Inventory 最终存在变化且存在 Post 订阅者时才创建事件.
     * Pre 新增到当前 Inventory 的修改也会包含在这里.
     *
     * @param scopes 不会再被 Pre 处理器修改的最终写集
     */
    void preparePost(@NotNull List<TransactionScope> scopes) {
        // 没有接收者时不做查找, 也不创建事件.
        if (this.postRecipients.isEmpty()) {
            return;
        }
        // 最终变化全部位于其他 Inventory 时, 不发送空事件.
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            if (scope.inventory() == this.inventory && !scope.slotChanges().isEmpty()) {
                this.post = new PostDeliveryQueue.PostDelivery(
                        this.postRecipients,
                        new InventoryPostUpdateEvent(this.inventory, this.reason, scopes)
                );
                return;
            }
        }
    }

    // 在内容写入时为 Post 事件预留队列位置, 防止后提交的事务先发出通知.
    void reservePost() {
        if (this.post != null) {
            this.postDeliveries.reserve(this.post);
        }
    }

    // 标记这笔事务的内容和提交后工作已经完成, 允许队列发送当前 Post 事件.
    void markPostReady() {
        if (this.post != null) {
            this.post.markReady();
        }
    }

    // 从队首依次发送已经准备完成的 Post 事件, 遇到尚未完成的事务时停止.
    void drainPost() {
        if (this.post != null) {
            this.postDeliveries.drain();
        }
    }
}
