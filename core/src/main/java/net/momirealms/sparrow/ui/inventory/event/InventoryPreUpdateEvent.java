package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.InteractionDraft;
import net.momirealms.sparrow.ui.inventory.InventoryTopology;
import net.momirealms.sparrow.ui.inventory.RootInventory;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Inventory 在事务提交前发出的更新事件.
 * <p>{@link #slotChanges()} 使用当前订阅 Inventory 的槽位坐标,
 * {@link #rootChanges()} 则保留整笔事务涉及的所有 RootInventory 变更.
 * <p>编辑窗口只覆盖当前同步处理器: 处理器返回后事件立即失效,
 * 任何线程再调用 {@link #setAfter} 都会失败.
 */
public final class InventoryPreUpdateEvent extends InventoryUpdateEvent {
    private ItemStack[][] plannedStates;                // 与 rootChanges 一一对应的规划基准状态
    @Nullable private final InventoryTopology topology; // 当前 Inventory 的槽位映射表, null 表示事件不支持编辑
    // 取得一个尚未参与事务的 RootInventory 的规划基准状态, null 表示事件不支持纳入新的 RootInventory
    @Nullable private final Function<RootInventory, ItemStack[]> baselines;
    @Nullable private final InteractionDraft interaction; // 触发本笔事务的交互副作用草稿, null 表示不是玩家交互
    private final Thread handlerThread;                 // 创建事件的处理器线程, setAfter 只允许它调用
    private volatile boolean editable;                  // 编辑窗口是否仍然打开
    private volatile boolean cancelled;                 // 是否已经有处理器取消整笔事务

    /**
     * 创建一个不支持编辑候选最终值的提交前事件.
     * <p>调用 {@link #setAfter} 会直接失败; 查询和取消事务仍然可用.
     *
     * @param inventory 当前事件使用其槽位坐标的 Inventory
     * @param reason 事务触发原因
     * @param slotChanges 投影到当前订阅 Inventory 后的槽位变更
     * @param rootChanges 整笔事务的完整 RootInventory 变更
     */
    @ApiStatus.Internal
    public InventoryPreUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges
    ) {
        this(inventory, reason, slotChanges, rootChanges, new ItemStack[rootChanges.size()][], null, null, null);
    }

    /**
     * 创建一个可以在当前同步处理器内修改候选最终值的提交前事件.
     *
     * @param inventory 当前事件使用其槽位坐标的 Inventory
     * @param reason 事务触发原因
     * @param slotChanges 投影到当前订阅 Inventory 后的槽位变更
     * @param rootChanges 整笔事务的完整 RootInventory 变更
     * @param plannedStates 每个参与 RootInventory 的规划基准状态
     * @param topology 当前 Inventory 的槽位映射表; 为 {@code null} 时事件不支持编辑
     * @param baselines 取得新纳入 RootInventory 基准状态的入口; 为 {@code null} 时事件不支持纳入新的 RootInventory
     * @param interaction 触发本笔事务的交互副作用草稿; 为 {@code null} 时事务不是玩家交互触发的
     */
    @ApiStatus.Internal
    public InventoryPreUpdateEvent(
            @NotNull SparrowInventory inventory,
            @NotNull UpdateReason reason,
            @NotNull List<SlotChange> slotChanges,
            @NotNull List<RootInventoryChange> rootChanges,
            ItemStack @NotNull [] @NotNull [] plannedStates,
            @Nullable InventoryTopology topology,
            @Nullable Function<RootInventory, ItemStack[]> baselines,
            @Nullable InteractionDraft interaction
    ) {
        super(inventory, reason, slotChanges, rootChanges);
        if (plannedStates.length != rootChanges.size()) {
            throw new IllegalArgumentException("planned state count does not match root change count");
        }
        this.plannedStates = plannedStates;
        this.topology = topology;
        this.baselines = baselines;
        this.interaction = interaction;
        // 事件在派发方线程上构造后立即交给处理器, 构造线程就是处理器线程.
        this.handlerThread = Thread.currentThread();
        this.editable = topology != null;
    }

    /**
     * 使用当前 {@link #inventory()} 的槽位坐标重写候选最终值.
     * <p>该槽位映射到的 RootInventory 必须已经参与本次事务.
     *
     * @param slot 当前 Inventory 的槽位
     * @param after 新的候选最终值, {@code null} 表示清空槽位
     * @throws IndexOutOfBoundsException 当前 Inventory 不包含该槽位
     * @throws IllegalArgumentException 该槽位对应的 RootInventory 未参与本次事务
     * @throws IllegalStateException 当前同步处理器已经退出, 或从其他线程调用
     */
    public void setAfter(int slot, @Nullable ItemStack after) {
        InventoryTopology topology = this.editableTopology();
        this.setRootAfter(topology.rootOf(slot), topology.rootSlotOf(slot), after);
    }

    /**
     * 使用 RootInventory 槽位坐标重写候选最终值.
     * <p>{@code rootInventory} 必须是当前 {@link #rootChanges()} 中某个变更组的 {@link RootInventoryChange#inventory()}
     * 返回的同一实例. 可以修改该 RootInventory 内原事务没有写到的槽位; 想写一个还没参与的 RootInventory,
     * 先调用 {@link #include(RootInventory)} 把它纳入进来.
     *
     * @param rootInventory 本次事务已经参与的 RootInventory
     * @param rootSlot RootInventory 槽位
     * @param after 新的候选最终值, {@code null} 表示清空槽位
     * @throws NullPointerException rootInventory 为 {@code null}
     * @throws IndexOutOfBoundsException RootInventory 不包含该槽位
     * @throws IllegalArgumentException 传入的 Inventory 不是本次事务的参与 RootInventory
     * @throws IllegalStateException 当前同步处理器已经退出, 或从其他线程调用
     */
    public void setAfter(@NotNull RootInventory rootInventory, int rootSlot, @Nullable ItemStack after) {
        this.setRootAfter(Objects.requireNonNull(rootInventory, "rootInventory"), rootSlot, after);
    }

    // 在编辑窗口内重写指定 RootInventory 槽位的候选最终值, 两个 {@link #setAfter} 重载共用.
    private void setRootAfter(@NotNull RootInventory rootInventory, int rootSlot, @Nullable ItemStack after) {
        InventoryTopology topology = this.editableTopology();

        // 找出该 RootInventory 在本次事务中的位置, 不允许引入新的 RootInventory.
        List<RootInventoryChange> rootChanges = this.rootChanges();
        int rootIndex = -1;
        for (int i = 0; i < rootChanges.size(); i++) {
            if (rootChanges.get(i).inventory() == rootInventory) {
                rootIndex = i;
                break;
            }
        }
        if (rootIndex == -1) {
            throw new IllegalArgumentException("inventory is not a participating RootInventory");
        }

        // 该槽位已有变更时替换其候选最终值并保留原 before, 否则以规划基准状态为 before 追加新变更.
        @Nullable ItemStack[] planned = this.plannedStates[rootIndex];
        Objects.checkIndex(rootSlot, planned.length);
        RootInventoryChange rootChange = rootChanges.get(rootIndex);
        List<SlotChange> current = rootChange.slotChanges();
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

        // 用重写后的变更组替换事件快照, 当前 Inventory 的槽位变更按映射表重新投影.
        List<RootInventoryChange> rewrittenRoots = new ArrayList<>(rootChanges);
        rewrittenRoots.set(rootIndex, new RootInventoryChange(rootInventory, updated));
        List<RootInventoryChange> immutableRoots = List.copyOf(rewrittenRoots);
        this.replaceChanges(topology.project(immutableRoots), immutableRoots);
    }

    /**
     * 把一个尚未参与本次事务的 RootInventory 纳入进来, 之后就能对它调用 {@link #setAfter(RootInventory, int, ItemStack)}.
     * <p>纳入之后, 它与原有参与者一起成功或一起回滚:
     * <pre>{@code
     * // 玩家往 A 放入泥土时, B 同步放入等量钻石
     * if (event.include(vault) ) {
     *     event.setAfter(vault, 0, new ItemStack(Material.DIAMOND, dirt.getAmount()));
     * }
     * }</pre>
     * <p>纳入必须是刻意动作, 因此 {@code setAfter} 对未纳入的 RootInventory 仍然直接抛异常, 不会自动纳入.
     * 只纳入却没有写任何槽位, 等于没有纳入.
     * <p>新纳入的 RootInventory 有三条与原有参与者不同的语义:
     * <ul>
     *     <li>它<b>不参与本轮 Pre</b> —— 否则它的处理器又能拉进下一个, 递归没有终点; 但它照常收到 Post.</li>
     *     <li>它的基准状态取纳入那一刻的内容, <b>不会先同步外部容器</b> —— 事务中段刷新引用根会重入事件系统.</li>
     *     <li>写进它的内容<b>不经过槽级放入规则过滤</b> —— 放入规则是给外部放入用的, 处理器本身就是决定内容的一方.</li>
     * </ul>
     *
     * @param rootInventory 要纳入本次事务的 RootInventory
     * @return 成功纳入返回 {@code true}; 它已经参与本次事务时返回 {@code false}
     * @throws IllegalStateException 当前同步处理器已经退出, 从其他线程调用, 或本事件不支持纳入新的 RootInventory
     */
    public boolean include(@NotNull RootInventory rootInventory) {
        InventoryTopology topology = this.editableTopology();
        Function<RootInventory, ItemStack[]> baselines = this.baselines;
        if (baselines == null) {
            throw new IllegalStateException("pre-update event cannot bring new RootInventories into this transaction");
        }

        List<RootInventoryChange> rootChanges = this.rootChanges();
        for (int i = 0; i < rootChanges.size(); i++) {
            if (rootChanges.get(i).inventory() == rootInventory) {
                return false;
            }
        }

        // 基准状态与新变更组一起追加到末尾, 两者的下标对应关系与原有参与者一致.
        ItemStack[] baseline = baselines.apply(rootInventory);
        ItemStack[][] expandedStates = Arrays.copyOf(this.plannedStates, this.plannedStates.length + 1);
        expandedStates[expandedStates.length - 1] = baseline;
        List<RootInventoryChange> expandedRoots = new ArrayList<>(rootChanges);
        expandedRoots.add(new RootInventoryChange(rootInventory, List.of()));
        List<RootInventoryChange> immutableRoots = List.copyOf(expandedRoots);
        this.plannedStates = expandedStates;
        this.replaceChanges(topology.project(immutableRoots), immutableRoots);
        return true;
    }

    /**
     * 返回本笔事务的交互副作用草稿, 用来改写提交后的光标, 副手和掉落物.
     * <p>{@link #setAfter} 只能改容器里的内容, 光标不属于任何 RootInventory. 缩小一个槽位的最终值时,
     * 差额该不该回到光标, 只有处理器自己知道, 因此需要在这里一并写清楚:
     * <pre>{@code
     * // 炉子这次只吃得下 10 个, 其余 54 个退回光标
     * event.setAfter(0, null);
     * InteractionDraft interaction = event.interaction();
     * if (interaction != null) interaction.cursor(coal.asQuantity(54));
     * }</pre>
     * <p>返回的草稿与本笔事务的其他 Pre 处理器共用, 上一个处理器写下的结果就是这里读到的内容.
     *
     * @return 玩家交互触发的事务返回可编辑的副作用草稿; API 写入与外部同步返回 {@code null}
     * @throws IllegalStateException 当前同步处理器已经退出, 或从其他线程调用
     */
    @Nullable
    public InteractionDraft interaction() {
        this.editableTopology();
        return this.interaction;
    }

    // 校验编辑窗口仍然打开, 且调用方就是创建事件的处理器线程.
    @NotNull
    private InventoryTopology editableTopology() {
        InventoryTopology topology = this.topology;
        if (topology == null || !this.editable || Thread.currentThread() != this.handlerThread) {
            throw new IllegalStateException("pre-update event can only be edited inside its synchronous handler");
        }
        return topology;
    }

    /**
     * 关闭编辑窗口, 由事件派发方在处理器返回后调用.
     * <p>关闭后任何 {@link #setAfter} 调用都会失败, 防止逃逸出去的事件引用继续修改事务.
     */
    @ApiStatus.Internal
    public void closeEditing() {
        this.editable = false;
    }

    /**
     * 取消整笔事务, 或恢复前面处理器留下的取消.
     * <p>取消状态按订阅顺序在处理器之间传递: 当前处理器看到的初始值就是前面处理器留下的结果,
     * 传入 {@code false} 会清除这个取消, 事务照常提交.
     * <p>当前处理器抛出异常时, 这次调用连同它通过 {@link #setAfter} 做的候选值修改一起被丢弃,
     * 后面的处理器看到的仍然是它执行前的取消状态.
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
