package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
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
 * 不持有槽位状态, 把自身槽位换算到一个或多个 RootInventory 的 Inventory.
 * <p>本类集中实现 ViewInventory 共有的单槽转发, 多 RootInventory 规划和刷新行为;
 * 具体子类只需定义固定的槽位映射, 内容副本和遍历顺序.
 */
public abstract non-sealed class ViewInventory extends SparrowInventory {

    ViewInventory() {
    }

    @Override
    @Nullable
    public ItemStack itemAt(int slot) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().itemAt(anchor.rootSlot());
    }

    @Override
    @Nullable
    public ItemStack unsafeItemAt(int slot) {
        SlotKey.Anchor anchor = this.resolveSlot(slot);
        return anchor.root().unsafeItemAt(anchor.rootSlot());
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

    /**
     * 按 ViewInventory 结构的声明顺序收集背后的全部 RootInventory, 由集合按实例身份去重.
     * 即使当前 ViewInventory 没有可见槽, 也必须保留其底层 RootInventory 供 {@link #refresh()} 使用.
     *
     * @param roots 接收 RootInventory 的集合
     */
    abstract void collectRoots(@NotNull LinkedHashSet<RootInventory> roots);

    /**
     * 收集一个 ViewInventory 子节点背后的 RootInventory.
     * RootInventory 节点直接加入集合, ViewInventory 节点继续按自身结构展开.
     *
     * @param inventory 要展开的子 Inventory
     * @param roots 接收 RootInventory 的集合
     */
    static void collectRootsFrom(@NotNull SparrowInventory inventory, @NotNull LinkedHashSet<RootInventory> roots) {
        switch (inventory) {
            case RootInventory root -> roots.add(root);
            case ViewInventory view -> view.collectRoots(roots);
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
     * 读取当前 ViewInventory 的全部槽位, 并记住它们来自哪些 RootInventory.
     * <p>同一个 RootInventory 在一次规划中只读取一次, 防止同一次计算混入两个时刻的内容.
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
     * 把当前 ViewInventory 的槽位变更换算并分配给实际持有事务状态的 RootInventory.
     *
     * @param plannedByRoot 计算修改结果时读到的各 RootInventory 内容
     * @param topology 当前 Inventory 与 RootInventory 之间的槽位关系
     * @param logicalDeltas 当前 Inventory 中要进行的槽位变更
     * @return 每个 RootInventory 实际需要执行的槽位变更
     */
    private static List<TransactionScope> toScopes(
            Map<RootInventory, @Nullable ItemStack[]> plannedByRoot,
            InventoryTopology topology,
            List<SlotChange> logicalDeltas
    ) {
        Map<RootInventory, List<SlotChange>> deltasByRoot = new LinkedHashMap<>();
        for (int i = 0; i < logicalDeltas.size(); i++) {
            SlotChange delta = logicalDeltas.get(i);
            SlotKey.Anchor anchor = topology.anchorAt(delta.slot());
            deltasByRoot.computeIfAbsent(anchor.root(), root -> new ArrayList<>()).add(delta.withSlot(anchor.rootSlot()));
        }

        List<TransactionScope> scopes = new ArrayList<>(deltasByRoot.size());
        for (Map.Entry<RootInventory, List<SlotChange>> entry : deltasByRoot.entrySet()) {
            scopes.add(new TransactionScope(entry.getKey(), plannedByRoot.get(entry.getKey()), entry.getValue()));
        }
        return scopes;
    }
}
