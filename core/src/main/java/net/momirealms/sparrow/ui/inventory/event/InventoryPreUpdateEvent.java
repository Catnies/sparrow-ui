package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.transaction.InteractionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
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
    @Nullable private final InteractionDraft interaction; // 光标、副手和掉落物的草稿, 整条 Pre 链共用一份; null 表示这笔事务不是玩家交互引起的
    private final Thread handlerThread;                 // 建这个事件的那个线程. setAfter 只认它, 从别的线程改会抛异常
    private volatile boolean editable;                  // 还能不能改. 处理器正常返回之后就关掉, 逃逸出去的事件引用改不动事务了
    private volatile boolean cancelled;                 // 前面有没有处理器已经取消了整笔事务

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
     * 读取当前 Inventory 指定槽位的候选物品副本, 空槽返回 {@code null}.
     *
     * @param slot 当前 Inventory 的槽位
     * @return 本事件可见的候选物品副本
     * @throws IndexOutOfBoundsException 槽号超出规划时的 Inventory 大小
     * @see #after(SparrowInventory, int)
     */
    @Nullable
    public ItemStack after(int slot) {
        return this.after(this.inventory(), slot);
    }

    /**
     * 读取参与库存指定槽位的候选物品副本, 未编辑的槽位使用规划时的内容.
     * <p>读取本事件最后可见的候选快照, 处理器结束后仍可查询. 新库存需要先通过 {@link #include(SparrowInventory)} 纳入.
     *
     * @param inventory 本事件已经参与的 Inventory 实例
     * @param slot Inventory 槽位
     * @return 候选物品的独立副本, 候选槽位为空时返回 {@code null}
     * @throws IllegalArgumentException 传入的 Inventory 没有参与本事件
     * @throws IndexOutOfBoundsException 槽号超出规划时的 Inventory 大小
     */
    @Nullable
    public ItemStack after(@NotNull SparrowInventory inventory, int slot) {
        List<TransactionScope> scopes = this.scopes();
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            if (scope.inventory() != inventory) {
                continue;
            }
            ItemStack[] planned = scope.planned();
            Objects.checkIndex(slot, planned.length);
            List<SlotChange> changes = scope.slotChanges();
            for (int j = 0; j < changes.size(); j++) {
                SlotChange change = changes.get(j);
                if (change.slot() == slot) {
                    return change.after();
                }
            }
            return ItemUtils.copyOrNull(planned[slot]);
        }
        throw new IllegalArgumentException("inventory is not participating in this transaction");
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

    // 两个 setAfter 重载共用的实现, 改的都是某个 Inventory 某一格的候选最终值.
    private void setRootAfter(@NotNull SparrowInventory inventory, int rootSlot, @Nullable ItemStack after) {
        this.checkEditable();

        // 按实例找它排在本笔事务第几位. 找不到就直说, 这里不会顺手把它拉进来,
        // 拉进新 Inventory 必须是 include 那个刻意动作, 不能靠一次 setAfter 顺带发生.
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

        // before 保持最初那份, 只换 after. 后面的处理器还要靠 before 判断这一格原本是什么.
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

        // 用改过的写集换掉事件里那份快照, slotChanges 这类惰性视图会跟着一起失效重算
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
     *     <li>它的基准状态取纳入那一刻的内容, <b>不会先同步外部容器</b>. 事务跑到一半去刷新 ReferencingInventory 会当场派发一笔嵌套事务, 重入整个事件系统.</li>
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

        // 基准和变更组本来就绑在同一条写集里, 直接追加到末尾就行, 不用另外维护谁对应谁.
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

    // 确认现在还能改, 并且改的人就是当初建这个事件的那个线程.
    private void checkEditable() {
        if (!this.editable || Thread.currentThread() != this.handlerThread) {
            throw new IllegalStateException("pre-update event can only be edited inside its synchronous handler");
        }
    }

    // 处理器一返回就关掉编辑. 有人把事件对象存起来慢慢改的话, 到这里就改不动了.
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
