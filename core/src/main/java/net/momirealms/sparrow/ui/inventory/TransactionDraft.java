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

/**
 * 保存 Pre 事件正在讨论的 "最终提交内容".
 * <p>事务刚进入 Pre 阶段时, 这里保存调用方原本想写入的内容. 一个处理器正常返回后,
 * 它通过 {@code setAfter} 留下的修改会成为新的草稿, 下一个处理器看到的就是这份新结果.
 * 如果处理器抛出异常, {@link TransactionNotification} 不会接纳它留下的修改.
 * <p>Pre 处理器可以改动已经参与事务的 Inventory 中任意槽位, 也可以把新的 Inventory
 * 纳入本笔事务, 但不能移除或换位已经参与的 Inventory —— 提交阶段靠这个顺序区分原有参与者和新纳入者.
 * 已经参与的 Inventory 在事务开始规划时看到的内容始终不变, 提交时还要用它判断期间是否插入了
 * 其他写操作; 新纳入的 Inventory 则以纳入那一刻的内容为基准.
 */
final class TransactionDraft {
    private List<TransactionScope> scopes;         // 按 Inventory 分组的当前待提交内容, 每一条自带该 Inventory 的规划基准
    // Pre 期间新纳入的 Inventory 的规划基准. 同一笔事务内只发一次, 保证事件与提交阶段用的是同一份.
    private final IdentityHashMap<SparrowInventory, SparrowInventory.PlannedRoot> includedRoots = new IdentityHashMap<>();

    /**
     * 校验事务形状并创建 Pre 阶段草稿.
     * <p>草稿在闸门跑用户代码之前就要建好, 校验因此跟着一起前移: 形状非法的事务在任何监听器
     * 有机会写入之前就被拒绝, 而不是等它们白忙一场之后才抛出.
     *
     * @param scopes 每个 Inventory 原本准备提交的修改
     * @throws IllegalArgumentException 当事务形状非法时(没有写集, 某个写集没有变更, 槽号越界, 同一个 Inventory 或同一个槽被写两次)
     */
    TransactionDraft(@NotNull List<TransactionScope> scopes) {
        this.scopes = validate(scopes);
    }

    private TransactionDraft() {
        this.scopes = List.of();
    }

    /**
     * 创建一份还没有任何写集的草稿, 供规划期算不出写集的交互攒下闸门的写入.
     * <p>空写集没有形状可以校验, 因此这里不走 {@link #validate}; 第一次 {@link #setAfter}
     * 之后的每一次改写仍然照常校验.
     *
     * @return 空草稿
     */
    @NotNull
    static TransactionDraft empty() {
        return new TransactionDraft();
    }

    /**
     * 返回当前准备交给提交阶段的修改.
     *
     * @return 按 Inventory 分组的待提交内容
     */
    @NotNull
    List<TransactionScope> scopes() {
        return this.scopes;
    }

    /**
     * 返回当前准备提交的完整变更, 供事务结果直接使用.
     *
     * @return 按参与顺序排列的 Inventory 变更
     */
    @NotNull
    List<InventoryChange> rootChanges() {
        List<InventoryChange> changes = new ArrayList<>(this.scopes.size());
        for (int i = 0; i < this.scopes.size(); i++) {
            changes.add(this.scopes.get(i).change());
        }
        return List.copyOf(changes);
    }

    /**
     * 取得一个尚未参与事务的 Inventory 的规划基准, 供 Pre 事件把它纳入本笔事务.
     * <p>基准直接取当前内容, 不调用 {@link SparrowInventory#prepareWrite()}: 事务中段刷新引用容器
     * 会提交一笔嵌套的 External 事务并派发它自己的 Post, 相当于在 Pre 与 commit 之间重入事件系统.
     * <p>同一笔事务内对同一个 Inventory 只发一次. 处理器抛出异常导致纳入被丢弃时, 缓存的基准
     * 仍然保留, 后面的处理器再次纳入它时看到的是同一份基准, 不会因为中途被别人写过而错位.
     *
     * @param inventory 要纳入的 Inventory
     * @return 纳入那一刻签发的规划基准
     */
    @NotNull
    SparrowInventory.PlannedRoot rootOf(@NotNull SparrowInventory inventory) {
        return this.includedRoots.computeIfAbsent(inventory, SparrowInventory::openPlan);
    }

    /**
     * 为 Pre 事件的纳入动作构造一条空写集: 基准经 {@link #rootOf} 签发, 与后续提交阶段共用同一份.
     *
     * @param inventory 要纳入的 Inventory
     * @return 持有该 Inventory 规划基准的空写集
     */
    @NotNull
    TransactionScope includeScope(@NotNull SparrowInventory inventory) {
        return new TransactionScope(this.rootOf(inventory), List.of());
    }

    /**
     * 在事务开始之前直接改写某个槽位的候选最终值.
     * <p>供交互闸门(Bukkit 与 Sparrow 事件)使用: 它们跑在事务之外, 写入立即生效, 没有 Pre 处理器
     * 那种"抛异常就整组丢弃"的分组语义. Inventory 还没参与本笔事务时自动纳入, 并且因为这里还没进入
     * 事务, 纳入前可以安全地刷新引用容器, 让基准反映外部容器的当前内容.
     * <p>不经过槽级放入规则过滤: 放入规则是给外部放入用的, 监听器本身就是决定内容的一方.
     *
     * @param inventory 要写入的 Inventory
     * @param rootSlot Inventory 槽位
     * @param after 新的候选最终值, {@code null} 表示清空槽位
     * @throws IndexOutOfBoundsException Inventory 不包含该槽位
     * @throws IllegalArgumentException 改写后的写集非法
     */
    void setAfter(@NotNull SparrowInventory inventory, int rootSlot, @Nullable ItemStack after) {
        int rootIndex = this.indexOf(inventory);
        if (rootIndex < 0) {
            // 闸门跑在事务之外, 这里刷新引用容器不会重入事件系统.
            inventory.prepareWrite();
        }
        SparrowInventory.PlannedRoot basis = rootIndex < 0 ? this.rootOf(inventory) : this.scopes.get(rootIndex).basis();
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

    /**
     * 接纳一个正常返回的 Pre 处理器留下的最终值, 让后续处理器和提交阶段继续使用它.
     * <p>处理器可以改动原有 Inventory 里的槽位, 也可以在末尾追加新纳入的 Inventory,
     * 但不能移除或换位已经参与的那些. 只纳入却没有写任何槽位的 Inventory 等于没有纳入,
     * 会在这里被丢掉. 新结果会再次检查槽位范围和重复写入, 检查失败时不会替换当前草稿.
     *
     * @param scopes 处理器执行后的完整写集
     * @throws IllegalArgumentException 当处理器移除或换位了参与事务的 Inventory, 或产生了非法、冲突的槽位修改时
     */
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

    /**
     * 检查一份写集能不能安全提交, 并整理成不可修改列表.
     * <p>要求事务至少修改一个槽位, 槽号没有越界, 同一个 Inventory 只出现一次. 同一个槽位或同一个真实外部
     * 槽位被写两次时直接拒绝整笔事务, 避免提交顺序悄悄决定最终结果.
     *
     * @param scopes 准备提交的各组 Inventory 修改
     * @return 确认没有冲突的不可修改列表, 顺序与传入一致
     * @throws IllegalArgumentException 当没有可提交的修改, 槽号越界, 同一个 Inventory 出现多次, 或多个修改写到同一真实槽位时
     */
    @NotNull
    private static List<TransactionScope> validate(@NotNull List<TransactionScope> scopes) {
        // 一笔事务必须实际修改至少一个 Inventory.
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("transaction requires at least one scope");
        }
        IdentityHashMap<SparrowInventory, Boolean> seenInventories = new IdentityHashMap<>();
        // 只有跨 Inventory 时, 不同 Inventory 才可能映射到同一个外部槽位.
        HashSet<SlotKey> seenPhysicalSlots = scopes.size() > 1 ? new HashSet<>() : null;
        // 规划器可以采纳物品实例而不复制(见 SlotChange.adopt), 搬运语义下每个实例至多有一个落点.
        // 同一实例落进两个槽位会让两处共享同一对象, 之后任何一方的改动都会串到另一处, 说明写集构造有误.
        IdentityHashMap<ItemStack, Boolean> seenAfterItems = new IdentityHashMap<>();
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
                @Nullable ItemStack after = change.unsafeAfter();
                if (after != null && seenAfterItems.put(after, Boolean.TRUE) != null) {
                    throw new IllegalArgumentException("transaction writes the same item instance into more than one slot");
                }
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
