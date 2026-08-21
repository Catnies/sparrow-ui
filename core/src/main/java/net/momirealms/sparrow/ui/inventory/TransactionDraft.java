package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.InventoryChange;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

// Pre 事件正在讨论的"最终提交内容". 事务刚进 Pre 时装的是调用方原本想写的东西, 每个处理器正常返回后,
// 它留下的修改就成了新草稿, 下一个处理器看到的是这份新结果; 处理器抛异常的话, 它那一组修改会被整组丢掉.
// 处理器可以改已参与 Inventory 的任意槽位, 也可以纳入新的 Inventory, 但不许移除或换位已参与的那些 ——
// 提交阶段就靠这个顺序区分原有参与者和新纳入者. 已参与者的规划内容自事务开始就锁定, 提交时还要拿它判断
// 期间有没有别人插队; 新纳入者则以纳入那一刻的内容为基准.
final class TransactionDraft {
    private List<TransactionScope> scopes;         // 按 Inventory 分组的当前待提交内容, 每一条自带该 Inventory 的规划基准
    // Pre 期间新纳入的 Inventory 的规划基准. 同一笔事务内只发一次, 保证事件与提交阶段用的是同一份.
    private final IdentityHashMap<SparrowInventory, PlannedRoot> includedRoots = new IdentityHashMap<>();

    // 草稿要在闸门跑用户代码之前就建好, 校验因此跟着一起前移: 形状非法的事务在任何监听器动手之前就被拒, 不让它们白忙一场.
    TransactionDraft(@NotNull List<TransactionScope> scopes) {
        this.scopes = validate(scopes);
    }

    private TransactionDraft() {
        this.scopes = List.of();
    }

    // 给规划期算不出写集的交互用的空草稿, 闸门的写入先攒进来. 空写集没有形状可校验, 所以这里不走 validate,
    // 第一次 setAfter 之后的每次改写照常校验.
    @NotNull
    static TransactionDraft empty() {
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

    // 签发一个尚未参与事务的 Inventory 的规划基准, 供 Pre 事件把它拉进本笔事务.
    // 这里直接取当前内容, 不走 prepareWrite: 事务中段刷新引用容器会顺手提交一笔嵌套 External 事务并派发它自己的 Post,
    // 等于在 Pre 和 commit 之间重入了事件系统. 同一笔事务内一个 Inventory 只签一次, 处理器抛异常把纳入丢掉了,
    // 缓存的基准也留着, 后面的处理器再拉它进来看到的还是同一份, 不会因为中途被别人写过而错位.
    @NotNull
    PlannedRoot rootOf(@NotNull SparrowInventory inventory) {
        return this.includedRoots.computeIfAbsent(inventory, SparrowInventory::openPlan);
    }

    // 给 Pre 事件的纳入动作造一条空写集, 基准与后续提交阶段共用同一份.
    @NotNull
    TransactionScope includeScope(@NotNull SparrowInventory inventory) {
        return new TransactionScope(this.rootOf(inventory), List.of());
    }

    // 在事务开始之前直接改写某个槽位的候选最终值, 给交互闸门(Bukkit 与 Sparrow 事件)用: 它们跑在事务之外,
    // 写入立即生效, 没有 Pre 处理器那种"抛异常就整组丢弃"的分组语义. 也不过槽级放入规则 —— 放入规则是拦外部放入的,
    // 监听器本身就是决定内容的那一方.
    void setAfter(@NotNull SparrowInventory inventory, int rootSlot, @Nullable ItemStack after) {
        int rootIndex = this.indexOf(inventory);
        if (rootIndex < 0) {
            // 还没进事务, 这里刷新引用容器不会重入事件系统, 于是新纳入者的基准可以反映外部容器的当前内容
            inventory.prepareWrite();
        }
        PlannedRoot basis = rootIndex < 0 ? this.rootOf(inventory) : this.scopes.get(rootIndex).basis();
        @Nullable ItemStack[] planned = basis.planned();
        Objects.checkIndex(rootSlot, planned.length);

        // 该槽位已有变更时替换其候选最终值并保留原 before, 否则以规划基准状态为 before 追加新变更.
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

    // 接下一个正常返回的 Pre 处理器留下的最终值, 让后续处理器和提交阶段接着用. 整份新结果通过检查才替换草稿,
    // 一处不合法就整份不要.
    void accept(@NotNull List<TransactionScope> scopes) {
        // 事件仍持有原来的写集, 说明这个处理器没有改动最终值.
        if (scopes == this.scopes) {
            return;
        }
        // 提交阶段靠这个顺序区分原有参与者和 Pre 期间新纳入的 Inventory, 移除或换位都会错位.
        if (scopes.size() < this.scopes.size()) {
            throw new IllegalArgumentException("pre-update edit removed a participating inventory");
        }

        // 按原顺序核对每个已参与的 Inventory, 它们的规划基准状态已经绑在各自的写集里.
        List<TransactionScope> rewritten = new ArrayList<>(scopes.size());
        for (int i = 0; i < this.scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            if (scope.inventory() != this.scopes.get(i).inventory()) {
                throw new IllegalArgumentException("pre-update edit changed a participating inventory");
            }
            rewritten.add(scope);
        }
        // 末尾的新参与者一个槽位都没写的直接跳过, 免得空写集卡住整笔事务.
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
        // 只有跨 Inventory 时, 不同 Inventory 才可能映射到同一个外部槽位.
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
