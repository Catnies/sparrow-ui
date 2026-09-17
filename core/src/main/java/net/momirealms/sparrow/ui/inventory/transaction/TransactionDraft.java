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

// 一整条 Pre 链共用的同一份候选写集, 前一个处理器改完后一个接着看.
// <strong>最初那批参与者的顺序不能动, 新拉进来的只能排在末尾</strong>, 事务结果和事件里的顺序都按这个来.
@ApiStatus.Internal
public final class TransactionDraft {
    private List<TransactionScope> scopes;
    // 同一个 Inventory 在整条 Pre 链里只抓一次规划基准, 后面谁再写它都复用这一份.
    private final IdentityHashMap<SparrowInventory, PlannedRoot> includedRoots = new IdentityHashMap<>();

    public TransactionDraft(@NotNull List<TransactionScope> scopes) {
        this.scopes = validate(scopes);
    }

    private TransactionDraft() {
        this.scopes = List.of();
    }

    // 监听器那一路允许从空草稿起步, 毕竟这次点击可能本来就没有写集.
    // 形状校验推到第一次真写入之后再做, 空草稿本身不算非法.
    @NotNull
    public static TransactionDraft empty() {
        return new TransactionDraft();
    }

    @NotNull
    List<TransactionScope> scopes() {
        return this.scopes;
    }

    // 摊平成一份按参与顺序排好的变更列表, 事务结果直接拿它当返回值.
    @NotNull
    List<InventoryChange> rootChanges() {
        List<InventoryChange> changes = new ArrayList<>(this.scopes.size());
        for (int i = 0; i < this.scopes.size(); i++) {
            changes.add(this.scopes.get(i).change());
        }
        return List.copyOf(changes);
    }

    // 这里刻意不调 prepareWrite. 如果在 Pre 和提交之间去刷新 ReferencingInventory, 会当场派发一笔嵌套的 External 事务,
    // 那笔事务又会带出自己的 Post, 在外层还没提交的时候重入整个事件系统.
    @NotNull
    PlannedRoot rootOf(@NotNull SparrowInventory inventory) {
        return this.includedRoots.computeIfAbsent(inventory, SparrowInventory::openPlan);
    }

    // Pre 里调 include 拉进新 Inventory 时走这里, 先给它一条空写集占位.
    // 基准就是这一刻的内容, 提交阶段用的还是这同一份.
    @NotNull
    TransactionScope includeScope(@NotNull SparrowInventory inventory) {
        return new TransactionScope(this.rootOf(inventory), List.of());
    }

    // 监听器直接改写某一格的最终值. 这类写入不过 AccessRule,
    // 规则是用来拦外部放入的, 而监听器本身就是决定结果的那一方.
    public void setAfter(@NotNull SparrowInventory inventory, int rootSlot, @Nullable ItemStack after) {
        int rootIndex = this.indexOf(inventory);
        if (rootIndex < 0) {
            // 这时候还没进提交临界区, 所以刷新 ReferencingInventory 是安全的, 不会造成嵌套事务.
            inventory.prepareWrite();
        }
        PlannedRoot basis = rootIndex < 0 ? this.rootOf(inventory) : this.scopes.get(rootIndex).basis();
        @Nullable ItemStack[] planned = basis.planned();
        Objects.checkIndex(rootSlot, planned.length);

        // before 保持最初那份, 只换 after. 事件里的 before 必须一直是真实起点, 否则处理器之间会互相误读.
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

    // 按实例找某个 Inventory 排在写集第几位, 还没参与就返回 -1.
    private int indexOf(@NotNull SparrowInventory inventory) {
        for (int i = 0; i < this.scopes.size(); i++) {
            if (this.scopes.get(i).inventory() == inventory) {
                return i;
            }
        }
        return -1;
    }

    // 一个 Pre 处理器正常返回之后, 把它改出来的写集收下, 后面的处理器接着从这份往下读.
    void accept(@NotNull List<TransactionScope> scopes) {
        if (scopes == this.scopes) {
            return;
        }
        // 原来那批参与者既不能被删掉也不能换位置, 新拉进来的只准出现在末尾.
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
        // 被 include 进来却一格都没改的, 不进最终写集, 免得白发一轮 Post.
        for (int i = this.scopes.size(); i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            if (scope.slotChanges().isEmpty()) {
                continue;
            }
            rewritten.add(scope);
        }
        // 整份新结果全部通过检查才替换草稿. 中途失败就保持原样, 不留半套改动.
        this.scopes = validate(rewritten);
    }

    // 检查一份写集能不能安全提交, 顺手整理成不可修改的列表.
    @NotNull
    private static List<TransactionScope> validate(@NotNull List<TransactionScope> scopes) {
        // 一笔事务总得真的改到点什么.
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("transaction requires at least one scope");
        }
        IdentityHashMap<SparrowInventory, Boolean> seenInventories = new IdentityHashMap<>();
        // 跨 Inventory 的写集还得多查一层, 两个 Inventory 可能映射到同一格真实位置.
        HashSet<SlotKey> seenPhysicalSlots = scopes.size() > 1 ? new HashSet<>() : null;
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            List<SlotChange> slotChanges = scope.slotChanges();
            if (slotChanges.isEmpty()) {
                throw new IllegalArgumentException("transaction scope has no slot changes");
            }
            // 同一个 Inventory 出现两组修改就直接拒掉. 两组各自基于哪份规划内容没法调和, 硬合会算错.
            SparrowInventory inventory = scope.inventory();
            if (seenInventories.put(inventory, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("transaction contains the same inventory more than once");
            }

            int size = scope.planned().length;
            HashSet<Integer> seenSlots = new HashSet<>();
            for (int j = 0; j < slotChanges.size(); j++) {
                SlotChange change = slotChanges.get(j);
                int slot = change.slot();
                // 槽号得落在规划时看到的那个尺寸里.
                if (slot < 0 || slot >= size) {
                    throw new IllegalArgumentException("slot " + slot + " is out of bounds for inventory size " + size);
                }
                // 同一格写两次, 没法判断该听哪一个.
                if (!seenSlots.add(slot)) {
                    throw new IllegalArgumentException("transaction contains conflicting slotChanges for slot " + slot);
                }
                // 两个 Inventory 指向同一格真实位置, 一笔事务里也只能写它一次.
                if (seenPhysicalSlots != null && !seenPhysicalSlots.add(inventory.physicalKey(slot))) {
                    throw new IllegalArgumentException("transaction contains conflicting slotChanges for the same physical slot");
                }
            }
        }
        return List.copyOf(scopes);
    }
}
