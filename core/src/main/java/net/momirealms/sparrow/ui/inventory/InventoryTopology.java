package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.InventoryDelta;
import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * 缓存当前 Inventory 与其 RootInventory 之间的槽位映射。
 *
 * <p>写入时，用它将当前槽位定位到对应的 RootInventory 槽位。
 * RootInventory 更新时，用它反查该变化在当前 Inventory 中对应的槽位。
 * 被隐藏的槽位没有对应关系，因此不会触发当前 Inventory 的更新通知。
 */
final class InventoryTopology {
    private final SlotKey.Anchor[] anchors;                                   // 每个当前槽位实际对应的根槽位
    private final RootInventory[] roots;                                      // 当前 Inventory 使用到的所有 RootInventory
    private final IdentityHashMap<RootInventory, int[]> logicalSlotsByRoot;   // 每个根槽位在当前 Inventory 中的位置, -1 表示不可见

    private InventoryTopology(
            @NotNull SlotKey.Anchor[] anchors,
            @NotNull RootInventory[] roots,
            @NotNull IdentityHashMap<RootInventory, int[]> logicalSlotsByRoot
    ) {
        this.anchors = anchors;
        this.roots = roots;
        this.logicalSlotsByRoot = logicalSlotsByRoot;
    }

    /**
     * 计算一个 Inventory 与其所有 RootInventory 之间的槽位对应关系.
     *
     * @param inventory 要处理的 Inventory
     * @return 可以重复使用的槽位映射
     */
    @NotNull
    static InventoryTopology compile(@NotNull SparrowInventory inventory) {
        // 先准备“当前槽位到根槽位”和“根槽位到当前槽位”两种查找方式.
        SlotKey.Anchor[] anchors = new SlotKey.Anchor[inventory.size()];
        List<RootInventory> roots = new ArrayList<>();
        IdentityHashMap<RootInventory, int[]> logicalSlotsByRoot = new IdentityHashMap<>();
        for (int logicalSlot = 0; logicalSlot < anchors.length; logicalSlot++) {
            SlotKey.Anchor anchor = inventory.resolveSlot(logicalSlot);
            anchors[logicalSlot] = anchor;

            // 新遇到一个 RootInventory 时, 先把它的槽位全部标记为当前 Inventory 不可见.
            int[] logicalSlots = logicalSlotsByRoot.get(anchor.root());
            if (logicalSlots == null) {
                logicalSlots = new int[anchor.root().size()];
                Arrays.fill(logicalSlots, -1);
                logicalSlotsByRoot.put(anchor.root(), logicalSlots);
                roots.add(anchor.root());
            }
            logicalSlots[anchor.rootSlot()] = logicalSlot;
        }
        return new InventoryTopology(anchors, roots.toArray(RootInventory[]::new), logicalSlotsByRoot);
    }

    /**
     * 查找当前槽位实际对应的 RootInventory 槽位.
     *
     * @param logicalSlot 当前 Inventory 的槽位
     * @return 对应的 RootInventory 和根槽位
     * @throws ArrayIndexOutOfBoundsException 当槽位越界时
     */
    @NotNull
    SlotKey.Anchor anchorAt(int logicalSlot) {
        return this.anchors[logicalSlot];
    }

    /**
     * 返回当前 Inventory 使用了多少个 RootInventory.
     *
     * @return RootInventory 数量
     */
    int rootCount() {
        return this.roots.length;
    }

    /**
     * 返回指定位置的 RootInventory.
     *
     * @param index  RootInventory 的位置
     * @return 对应的 RootInventory
     * @throws ArrayIndexOutOfBoundsException 当位置越界时
     */
    @NotNull
    RootInventory rootAt(int index) {
        return this.roots[index];
    }

    /**
     * 将 RootInventory 的变化转换为当前 Inventory 能看到的槽位变化.
     *
     * @param rootChanges 整笔事务在 RootInventory 中的变化
     * @return 当前 Inventory 能看到的槽位变化; 没有可见变化时返回空列表
     */
    @NotNull
    List<SlotDelta> project(@NotNull List<InventoryDelta> rootChanges) {
        List<SlotDelta> projected = new ArrayList<>();
        for (int i = 0; i < rootChanges.size(); i++) {
            InventoryDelta rootChange = rootChanges.get(i);

            // 整笔事务可能还修改了当前 Inventory 没有使用的 RootInventory.
            int[] logicalSlots = this.logicalSlotsByRoot.get(rootChange.inventory());
            if (logicalSlots == null) {
                continue;
            }

            // -1 表示该根槽位在当前 Inventory 中不可见, 其余槽位换成当前 Inventory 的编号.
            List<SlotDelta> rootDeltas = rootChange.deltas();
            for (int j = 0; j < rootDeltas.size(); j++) {
                SlotDelta rootDelta = rootDeltas.get(j);
                int logicalSlot = logicalSlots[rootDelta.slot()];
                if (logicalSlot != -1) {
                    projected.add(rootDelta.relocatedTo(logicalSlot));
                }
            }
        }
        return projected.isEmpty() ? List.of() : List.copyOf(projected);
    }
}
