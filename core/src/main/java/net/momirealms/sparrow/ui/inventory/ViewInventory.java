package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 不持有槽位状态、把自身槽位换算到一个或多个事务根的派生 Inventory.
 * <p>本类集中实现派生 Inventory 共有的单槽转发、多根规划和刷新行为;
 * 具体子类只需定义固定的槽位映射、内容副本和遍历顺序.
 */
abstract non-sealed class ViewInventory extends SparrowInventory {

    @Override
    @Nullable
    public ItemStack itemAt(int slot) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().itemAt(anchor.rootSlot());
    }

    @Override
    public int slotMaxStackSize(int slot) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().slotMaxStackSize(anchor.rootSlot());
    }

    @Override
    @NotNull
    public TransactionResult setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().setItem(reason, anchor.rootSlot(), item);
    }

    @Override
    @NotNull
    public TransactionResult forceSetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().forceSetItem(reason, anchor.rootSlot(), item);
    }

    @Override
    @NotNull
    public AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().putItem(reason, anchor.rootSlot(), item);
    }

    @Override
    @NotNull
    public TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().modifyItem(reason, anchor.rootSlot(), modifier);
    }

    @Override
    @NotNull
    public TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().changeAmount(reason, anchor.rootSlot(), change);
    }

    @Override
    public void refresh() {
        LinkedHashSet<RootInventory> roots = new LinkedHashSet<>();
        this.collectRoots(roots);
        for (RootInventory root : roots) {
            root.refresh();
        }
    }

    @Override
    @NotNull
    PlanContext openPlan() {
        return this.capturePlan(false);
    }

    @Override
    @NotNull
    PlanContext openPlanForWrite() {
        return this.capturePlan(true);
    }

    /**
     * 读取当前派生 Inventory 的全部槽位, 并记住它们来自哪些事务根.
     * <p>同一个事务根在一次规划中只读取一次, 防止同一次计算混入两个时刻的内容.
     *
     * @param forWrite 是否需要在读取前同步外部容器
     * @return 当前内容和后续提交所需的信息
     */
    @NotNull
    private PlanContext capturePlan(boolean forWrite) {
        int size = this.size();
        InventoryTopology topology = this.topology();
        Map<RootInventory, @Nullable ItemStack[]> plannedByRoot = new LinkedHashMap<>();
        @Nullable ItemStack[] logical = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            SlotKey.Anchor anchor = topology.anchorAt(slot);
            @Nullable ItemStack[] planned = plannedByRoot.computeIfAbsent(anchor.root(), root -> {
                if (forWrite) root.prepareWrite();
                return root.currentState();
            });
            logical[slot] = planned[anchor.rootSlot()];
        }
        return new PlanContext(logical, logicalDeltas -> toScopes(plannedByRoot, topology, logicalDeltas));
    }

    /**
     * 把当前派生 Inventory 的槽位变化分配给实际持有事务状态的根.
     *
     * @param plannedByRoot 计算修改结果时读到的各事务根内容
     * @param topology 当前 Inventory 与事务根之间的槽位关系
     * @param logicalDeltas 当前 Inventory 中要进行的槽位变化
     * @return 每个事务根实际需要执行的槽位变化
     */
    private static List<InventoryTransactions.Scope> toScopes(
            Map<RootInventory, @Nullable ItemStack[]> plannedByRoot,
            InventoryTopology topology,
            List<SlotDelta> logicalDeltas
    ) {
        Map<RootInventory, List<SlotDelta>> deltasByRoot = new LinkedHashMap<>();
        for (int i = 0; i < logicalDeltas.size(); i++) {
            SlotDelta delta = logicalDeltas.get(i);
            SlotKey.Anchor anchor = topology.anchorAt(delta.slot());
            deltasByRoot.computeIfAbsent(anchor.root(), root -> new ArrayList<>()).add(delta.relocatedTo(anchor.rootSlot()));
        }

        List<InventoryTransactions.Scope> scopes = new ArrayList<>(deltasByRoot.size());
        for (Map.Entry<RootInventory, List<SlotDelta>> entry : deltasByRoot.entrySet()) {
            scopes.add(new InventoryTransactions.Scope(entry.getKey(), plannedByRoot.get(entry.getKey()), entry.getValue()));
        }
        return scopes;
    }
}
