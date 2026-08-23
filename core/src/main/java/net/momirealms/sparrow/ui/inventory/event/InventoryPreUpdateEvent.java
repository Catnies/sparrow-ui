package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.transaction.InteractionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Inventory 在事务提交前发出的可编辑事件.
 * <p>{@link #slotChanges()} 使用当前 Inventory 的坐标, {@link #rootChanges()} 包含整笔事务.
 */
public final class InventoryPreUpdateEvent extends InventoryUpdateEvent {
    @Nullable private final Function<SparrowInventory, TransactionScope> includedScopes;
    @Nullable private final InteractionDraft interaction; // 触发本笔事务的交互副作用草稿, null 表示不是玩家交互
    private final Thread handlerThread;                 // 创建事件的处理器线程, setAfter 只允许它调用
    private volatile boolean editable;                  // 编辑窗口是否仍然打开
    private volatile boolean cancelled;                 // 是否已经有处理器取消整笔事务

    @ApiStatus.Internal
    public InventoryPreUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<TransactionScope> scopes,
            boolean editable,
            @Nullable Function<SparrowInventory, TransactionScope> includedScopes,
            @Nullable InteractionDraft interaction
    ) {
        super(inventory, reason, scopes);
        this.includedScopes = includedScopes;
        this.interaction = interaction;
        this.handlerThread = Thread.currentThread();
        this.editable = editable;
    }

    /**
     * 使用当前 {@link #inventory()} 的槽位坐标重写候选最终值.
     *
     * @param slot 当前 Inventory 的槽位
     * @param after 新的候选最终值, {@code null} 表示清空槽位
     * @throws IndexOutOfBoundsException 当前 Inventory 不包含该槽位
     * @throws IllegalStateException 当前同步处理器已经退出, 或从其他线程调用
     */
    public void setAfter(int slot, @Nullable ItemStack after) {
        this.setRootAfter(this.inventory(), slot, after);
    }

    /**
     * 使用另一个 Inventory 的槽位坐标重写候选最终值.
     * <p><strong>{@code inventory} 必须是当前 {@link #rootChanges()} 中变更组返回的同一实例</strong>.
     * 可以修改该 Inventory 内原事务没有写到的槽位; 想写一个还没参与的 Inventory,
     * 先调用 {@link #include(SparrowInventory)} 把它纳入进来.
     *
     * @param inventory 本次事务已经参与的 Inventory
     * @param rootSlot Inventory 槽位
     * @param after 新的候选最终值, {@code null} 表示清空槽位
     * @throws NullPointerException inventory 为 {@code null}
     * @throws IndexOutOfBoundsException Inventory 不包含该槽位
     * @throws IllegalArgumentException 传入的 Inventory 没有参与本次事务
     * @throws IllegalStateException 当前同步处理器已经退出, 或从其他线程调用
     */
    public void setAfter(@NotNull SparrowInventory inventory, int rootSlot, @Nullable ItemStack after) {
        this.setRootAfter(Objects.requireNonNull(inventory, "inventory"), rootSlot, after);
    }

    // 在编辑窗口内重写指定 Inventory 槽位的候选最终值, 两个 setAfter 重载共用.
    private void setRootAfter(@NotNull SparrowInventory inventory, int rootSlot, @Nullable ItemStack after) {
        this.checkEditable();

        // 找出该 Inventory 在本次事务中的位置, 不允许引入新的 Inventory.
        List<TransactionScope> scopes = this.scopes();
        int rootIndex = -1;
        for (int i = 0; i < scopes.size(); i++) {
            if (scopes.get(i).inventory() == inventory) {
                rootIndex = i;
                break;
            }
        }
        if (rootIndex == -1) {
            throw new IllegalArgumentException("inventory is not participating in this transaction");
        }

        // 保留最初的 before, 只替换候选最终值.
        TransactionScope scope = scopes.get(rootIndex);
        @Nullable ItemStack[] planned = scope.planned();
        Objects.checkIndex(rootSlot, planned.length);
        List<SlotChange> current = scope.slotChanges();
        List<SlotChange> updated = new ArrayList<>(current.size() + 1);
        boolean replaced = false;
        for (int i = 0; i < current.size(); i++) {
            SlotChange change = current.get(i);
            if (change.slot() == rootSlot) {
                updated.add(new SlotChange(rootSlot, change.unsafeBefore(), after));
                replaced = true;
            } else {
                updated.add(change);
            }
        }
        if (!replaced) {
            updated.add(new SlotChange(rootSlot, planned[rootSlot], after));
        }

        // 用重写后的写集替换事件快照, 当前 Inventory 的槽位变更跟着一起刷新
        List<TransactionScope> rewritten = new ArrayList<>(scopes);
        rewritten.set(rootIndex, scope.withSlotChanges(updated));
        this.replaceScopes(rewritten);
    }

    /**
     * 把一个尚未参与本次事务的 Inventory 纳入进来, 之后就能对它调用 {@link #setAfter(SparrowInventory, int, ItemStack)}.
     * <p>纳入之后, 它与原有参与者一起成功或一起回滚.
     * <pre>{@code
     * // 玩家往 A 放入泥土时, B 同步放入等量钻石
     * if (event.include(vault) ) {
     *     event.setAfter(vault, 0, new ItemStack(Material.DIAMOND, dirt.getAmount()));
     * }
     * }</pre>
     * <p>纳入必须是刻意动作, 因此 {@code setAfter} 对未纳入的 Inventory 仍然直接抛异常, 不会自动纳入.
     * <ul>
     *     <li>它<b>不参与本轮 Pre</b>, 但照常收到 Post, 不会递归展开.</li>
     *     <li>它的基准状态取纳入那一刻的内容, <b>不会先同步外部容器</b>. 事务中段刷新引用容器会重入事件系统.</li>
     *     <li>写进它的内容<b>不经过槽级放入规则过滤</b>. 放入规则是拦外部放入的, 处理器本身就是决定内容的一方.</li>
     * </ul>
     *
     * @param inventory 要纳入本次事务的 Inventory
     * @return 成功纳入返回 {@code true}; 它已经参与本次事务时返回 {@code false}
     * @throws IllegalStateException 当前同步处理器已经退出, 从其他线程调用, 或本事件不支持纳入新的 Inventory
     */
    public boolean include(@NotNull SparrowInventory inventory) {
        this.checkEditable();
        Function<SparrowInventory, TransactionScope> includedScopes = this.includedScopes;
        if (includedScopes == null) {
            throw new IllegalStateException("pre-update event cannot bring new inventories into this transaction");
        }

        List<TransactionScope> scopes = this.scopes();
        for (int i = 0; i < scopes.size(); i++) {
            if (scopes.get(i).inventory() == inventory) {
                return false;
            }
        }

        // 规划基准与新变更组绑在同一条写集里一起追加到末尾, 不需要另外维护对应关系.
        List<TransactionScope> expanded = new ArrayList<>(scopes);
        expanded.add(includedScopes.apply(inventory));
        this.replaceScopes(expanded);
        return true;
    }

    /**
     * 返回本笔事务共享的交互副作用草稿, 可改写提交后的光标, 副手和掉落物.
     * <p>槽位差额不会自动反映到草稿中, 处理器需要同时写明两边的最终结果.
     *
     * @return 玩家交互触发的事务返回可编辑的副作用草稿; API 写入与外部同步返回 {@code null}
     * @throws IllegalStateException 当前同步处理器已经退出, 或从其他线程调用
     */
    @Nullable
    public InteractionDraft interaction() {
        this.checkEditable();
        return this.interaction;
    }

    // 校验编辑窗口仍然打开, 且调用方就是创建事件的处理器线程.
    private void checkEditable() {
        if (!this.editable || Thread.currentThread() != this.handlerThread) {
            throw new IllegalStateException("pre-update event can only be edited inside its synchronous handler");
        }
    }

    // 处理器退出后关闭编辑窗口, 逃逸的事件引用不能继续修改事务.
    @ApiStatus.Internal
    public void closeEditing() {
        this.editable = false;
    }

    /**
     * 取消整笔事务, 或恢复前面处理器留下的取消.
     * <p>取消状态按订阅顺序在处理器之间传递, 当前处理器看到的初始值就是前面处理器留下的结果.
     *
     * @param cancelled {@code true} 取消整笔事务, {@code false} 让事务继续提交
     */
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * 返回当前取消状态.
     *
     * @return 当前事务是否会被取消
     */
    public boolean cancelled() {
        return this.cancelled;
    }
}
