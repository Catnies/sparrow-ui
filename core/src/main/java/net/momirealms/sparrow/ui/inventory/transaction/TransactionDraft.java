package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.InventoryChange;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.storage.SlotKey;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

// Pre 链共享的候选写集. <strong>原参与者顺序固定, 新参与者只能追加</strong>.
@ApiStatus.Internal
public final class TransactionDraft {
    private List<TransactionScope> scopes;
    // 同一 Inventory 在 Pre 期间只捕获一次规划基准.
    private final IdentityHashMap<SparrowInventory, PlannedRoot> includedRoots = new IdentityHashMap<>();

    public TransactionDraft(@NotNull List<TransactionScope> scopes) {
        this.scopes = validate(scopes);
    }

    private TransactionDraft() {
        this.scopes = List.of();
    }

    // 交互闸门可从空草稿开始, 第一次写入后再校验写集形状.
    @NotNull
    public static TransactionDraft empty() {
        return new TransactionDraft();
    }

    @NotNull
    List<TransactionScope> scopes() {
        return this.scopes;
    }

    // 摊成按参与顺序排列的变更列表, 直接交给事务结果用.
    @NotNull
    List<InventoryChange> rootChanges() {
        List<InventoryChange> changes = new ArrayList<>(this.scopes.size());
        for (int i = 0; i < this.scopes.size(); i++) {
            changes.add(this.scopes.get(i).change());
        }
        return List.copyOf(changes);
    }

    // Pre 纳入时不调用 prepareWrite, 避免在 Pre 与 commit 之间派发嵌套的 External 事务.
    @NotNull
    PlannedRoot rootOf(@NotNull SparrowInventory inventory) {
        return this.includedRoots.computeIfAbsent(inventory, SparrowInventory::openPlan);
    }

    // 给 Pre 事件的纳入动作造一条空写集, 基准与后续提交阶段共用同一份.
    @NotNull
    TransactionScope includeScope(@NotNull SparrowInventory inventory) {
        return new TransactionScope(this.rootOf(inventory), List.of());
    }

    // 交互闸门直接改写候选最终值, 写入不经过槽位放入规则.
    public void setAfter(@NotNull SparrowInventory inventory, int rootSlot, @Nullable ItemStack after) {
        int rootIndex = this.indexOf(inventory);
        if (rootIndex < 0) {
            // 闸门仍在事务外, 此时可以安全同步引用存储.
            inventory.prepareWrite();
        }
        PlannedRoot basis = rootIndex < 0 ? this.rootOf(inventory) : this.scopes.get(rootIndex).basis();
        @Nullable ItemStack[] planned = basis.planned();
        Objects.checkIndex(rootSlot, planned.length);

        // 保留最初的 before, 只替换候选最终值.
        List<SlotChange> current = rootIndex < 0 ? List.of() : this.scopes.get(rootIndex).slotChanges();
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

        List<TransactionScope> rewritten = new ArrayList<>(this.scopes);
        TransactionScope scope = new TransactionScope(basis, updated);
        if (rootIndex < 0) {
            rewritten.add(scope);
        } else {
            rewritten.set(rootIndex, scope);
        }
        this.scopes = validate(rewritten);
    }

    // 找出某个 Inventory 在当前写集中的位置, 尚未参与时返回 -1.
    private int indexOf(@NotNull SparrowInventory inventory) {
        for (int i = 0; i < this.scopes.size(); i++) {
            if (this.scopes.get(i).inventory() == inventory) {
                return i;
            }
        }
        return -1;
    }

    // 接纳通过形状校验的 Pre 修改, 后续处理器继续读取这份结果.
    void accept(@NotNull List<TransactionScope> scopes) {
        if (scopes == this.scopes) {
            return;
        }
        // 原参与者不可移除或换位, 新参与者只出现在末尾.
        if (scopes.size() < this.scopes.size()) {
            throw new IllegalArgumentException("pre-update edit removed a participating inventory");
        }

        List<TransactionScope> rewritten = new ArrayList<>(scopes.size());
        for (int i = 0; i < this.scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            if (scope.inventory() != this.scopes.get(i).inventory()) {
                throw new IllegalArgumentException("pre-update edit changed a participating inventory");
            }
            rewritten.add(scope);
        }
        // 未产生槽位变更的新参与者不进入最终写集.
        for (int i = this.scopes.size(); i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            if (scope.slotChanges().isEmpty()) {
                continue;
            }
            rewritten.add(scope);
        }
        // 只有整份新结果通过检查后, 才替换当前草稿.
        this.scopes = validate(rewritten);
    }

    // 检查一份写集能不能安全提交, 顺手整理成不可修改列表.
    @NotNull
    private static List<TransactionScope> validate(@NotNull List<TransactionScope> scopes) {
        // 一笔事务必须实际修改至少一个 Inventory.
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("transaction requires at least one scope");
        }
        IdentityHashMap<SparrowInventory, Boolean> seenInventories = new IdentityHashMap<>();
        // 跨 Inventory 写集还要排除物理槽位别名.
        HashSet<SlotKey> seenPhysicalSlots = scopes.size() > 1 ? new HashSet<>() : null;
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            List<SlotChange> slotChanges = scope.slotChanges();
            if (slotChanges.isEmpty()) {
                throw new IllegalArgumentException("transaction scope has no slot changes");
            }
            // 同一个 Inventory 出现两组修改时, 两组各自基于哪份规划内容无法调和, 因此拒绝整笔事务.
            SparrowInventory inventory = scope.inventory();
            if (seenInventories.put(inventory, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("transaction contains the same inventory more than once");
            }

            int size = scope.planned().length;
            HashSet<Integer> seenSlots = new HashSet<>();
            for (int j = 0; j < slotChanges.size(); j++) {
                SlotChange change = slotChanges.get(j);
                int slot = change.slot();
                // 槽号必须属于规划时看到的 Inventory 大小.
                if (slot < 0 || slot >= size) {
                    throw new IllegalArgumentException("slot " + slot + " is out of bounds for inventory size " + size);
                }
                // 同一个 Inventory 槽位出现两次时无法判断该采用哪个最终值.
                if (!seenSlots.add(slot)) {
                    throw new IllegalArgumentException("transaction contains conflicting slotChanges for slot " + slot);
                }
                // 两个 Inventory 映射到同一个真实槽位时, 一笔事务也不能把它写两次.
                if (seenPhysicalSlots != null && !seenPhysicalSlots.add(inventory.physicalKey(slot))) {
                    throw new IllegalArgumentException("transaction contains conflicting slotChanges for the same physical slot");
                }
            }
        }
        return List.copyOf(scopes);
    }
}
