package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.RootInventoryChange;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 保存 Pre 事件正在讨论的 "最终提交内容".
 * <p>事务刚进入 Pre 阶段时, 这里保存调用方原本想写入的内容. 一个处理器正常返回后,
 * 它通过 {@code setAfter} 留下的修改会成为新的草稿, 下一个处理器看到的就是这份新结果.
 * 如果处理器抛出异常, {@link TransactionNotification} 不会接纳它留下的修改.
 * <p>Pre 处理器可以改动已经参与事务的 RootInventory 中任意槽位, 也可以把新的 RootInventory
 * 纳入本笔事务, 但不能移除或换位已经参与的 RootInventory —— 它们与规划基准状态按下标一一对应.
 * 已经参与的 RootInventory 在事务开始规划时看到的内容始终不变, 提交时还要用它判断期间是否插入了
 * 其他写操作; 新纳入的 RootInventory 则以纳入那一刻的内容为基准.
 */
final class TransactionDraft {
    private List<TransactionScope> scopes;         // 按 RootInventory 分组的当前待提交内容
    private List<RootInventoryChange> rootChanges; // 同一份待提交内容, 转成事件可以直接读取的形式
    private ItemStack[][] plannedStates;           // 事务规划时看到的各个内容数组, 顺序与 rootChanges 一致
    // Pre 期间新纳入的 RootInventory 的基准状态. 同一笔事务内只取一次, 保证事件与提交阶段用的是同一份.
    private final IdentityHashMap<RootInventory, ItemStack[]> includedBaselines = new IdentityHashMap<>();

    /**
     * 校验事务形状并创建 Pre 阶段草稿.
     * <p>草稿在闸门跑用户代码之前就要建好, 校验因此跟着一起前移: 形状非法的事务在任何监听器
     * 有机会写入之前就被拒绝, 而不是等它们白忙一场之后才抛出.
     *
     * @param scopes 每个 RootInventory 原本准备提交的修改
     * @throws IllegalArgumentException 当事务形状非法时(没有 RootInventory 写集, 某个写集没有变更, 槽号越界, 同一个槽被写两次)
     */
    TransactionDraft(@NotNull List<TransactionScope> scopes) {
        List<TransactionScope> declared = TransactionValidator.validateAndMerge(scopes);
        this.scopes = declared;
        this.rootChanges = extractChanges(declared);
        this.plannedStates = plannedStatesOf(declared);
    }

    private TransactionDraft() {
        this.scopes = List.of();
        this.rootChanges = List.of();
        this.plannedStates = new ItemStack[0][];
    }

    /**
     * 创建一份还没有任何写集的草稿, 供规划期算不出写集的交互攒下闸门的写入.
     * <p>空写集没有形状可以校验, 因此这里不走 {@link TransactionValidator}; 第一次 {@link #setAfter}
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
     * @return 按 RootInventory 分组的待提交内容
     */
    @NotNull
    List<TransactionScope> scopes() {
        return this.scopes;
    }

    /**
     * 返回当前准备提交的完整变更, 供 Pre 事件和事务结果直接使用.
     *
     * @return 按参与顺序排列的 RootInventory 变更
     */
    @NotNull
    List<RootInventoryChange> rootChanges() {
        return this.rootChanges;
    }

    /**
     * 返回事务开始规划时看到的各个 RootInventory 内容数组.
     * <p>这些数组还用于取得 Pre 处理器后来新增槽位的原值, 顺序与 {@link #rootChanges()} 一致.
     *
     * @return 每个参与 RootInventory 原本使用的内容数组
     */
    @NotNull
    ItemStack[][] plannedStates() {
        return this.plannedStates;
    }

    /**
     * 取得一个尚未参与事务的 RootInventory 的规划基准状态, 供 Pre 事件把它纳入本笔事务.
     * <p>基准状态直接取当前内容, 不调用 {@link RootInventory#prepareWrite()}: 事务中段刷新引用根
     * 会提交一笔嵌套的 External 事务并派发它自己的 Post, 相当于在 Pre 与 commit 之间重入事件系统.
     * <p>同一笔事务内对同一个 RootInventory 只取一次. 处理器抛出异常导致纳入被丢弃时, 缓存的基准
     * 状态仍然保留, 后面的处理器再次纳入它时看到的是同一份基准, 不会因为中途被别人写过而错位.
     *
     * @param rootInventory 要纳入的 RootInventory
     * @return 纳入那一刻的内容数组引用
     */
    ItemStack @NotNull [] baselineOf(@NotNull RootInventory rootInventory) {
        return this.includedBaselines.computeIfAbsent(rootInventory, RootInventory::currentState);
    }

    /**
     * 在事务开始之前直接改写某个槽位的候选最终值.
     * <p>供交互闸门(Bukkit 与 Sparrow 事件)使用: 它们跑在事务之外, 写入立即生效, 没有 Pre 处理器
     * 那种"抛异常就整组丢弃"的分组语义. RootInventory 还没参与本笔事务时自动纳入, 并且因为这里还没进入
     * 事务, 纳入前可以安全地刷新引用根, 让基准反映外部容器的当前内容.
     * <p>不经过槽级放入规则过滤: 放入规则是给外部放入用的, 监听器本身就是决定内容的一方.
     *
     * @param rootInventory 要写入的 RootInventory
     * @param rootSlot RootInventory 槽位
     * @param after 新的候选最终值, {@code null} 表示清空槽位
     * @throws IndexOutOfBoundsException RootInventory 不包含该槽位
     * @throws IllegalArgumentException 改写后的写集非法
     */
    void setAfter(@NotNull RootInventory rootInventory, int rootSlot, @Nullable ItemStack after) {
        int rootIndex = this.indexOf(rootInventory);
        if (rootIndex < 0) {
            // 闸门跑在事务之外, 这里刷新引用根不会重入事件系统.
            rootInventory.prepareWrite();
        }
        @Nullable ItemStack[] planned = rootIndex < 0 ? this.baselineOf(rootInventory) : this.plannedStates[rootIndex];
        Objects.checkIndex(rootSlot, planned.length);

        // 该槽位已有变更时替换其候选最终值并保留原 before, 否则以规划基准状态为 before 追加新变更.
        List<SlotChange> current = rootIndex < 0 ? List.of() : this.rootChanges.get(rootIndex).slotChanges();
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
        TransactionScope scope = new TransactionScope(rootInventory, planned, updated);
        if (rootIndex < 0) {
            rewritten.add(scope);
        } else {
            rewritten.set(rootIndex, scope);
        }
        this.replace(TransactionValidator.validateAndMerge(rewritten));
    }

    // 找出某个 RootInventory 在当前写集中的位置, 尚未参与时返回 -1.
    private int indexOf(@NotNull RootInventory rootInventory) {
        for (int i = 0; i < this.scopes.size(); i++) {
            if (this.scopes.get(i).inventory() == rootInventory) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 接纳一个正常返回的 Pre 处理器留下的最终值, 让后续处理器和提交阶段继续使用它.
     * <p>处理器可以改动原有 RootInventory 里的槽位, 也可以在末尾追加新纳入的 RootInventory,
     * 但不能移除或换位已经参与的那些. 只纳入却没有写任何槽位的 RootInventory 等于没有纳入,
     * 会在这里被丢掉. 新结果会再次检查槽位范围和重复写入, 检查失败时不会替换当前草稿.
     *
     * @param rootChanges 处理器执行后的完整变更
     * @throws IllegalArgumentException 当处理器移除或换位了参与事务的 RootInventory, 或产生了非法、冲突的槽位修改时
     */
    void accept(@NotNull List<RootInventoryChange> rootChanges) {
        // 事件仍持有原来的列表, 说明这个处理器没有改动最终值.
        if (rootChanges == this.rootChanges) {
            return;
        }
        // plannedStates 与已参与的 RootInventory 按下标一一对应, 移除或换位都会静默错配 before 值.
        if (rootChanges.size() < this.scopes.size()) {
            throw new IllegalArgumentException("pre-update edit removed a participating RootInventory");
        }

        // 按原顺序核对每个 RootInventory, 并沿用事务规划时看到的内容数组.
        List<TransactionScope> rewritten = new ArrayList<>(rootChanges.size());
        for (int i = 0; i < this.scopes.size(); i++) {
            TransactionScope scope = this.scopes.get(i);
            RootInventoryChange rootChange = rootChanges.get(i);
            if (rootChange.inventory() != scope.inventory()) {
                throw new IllegalArgumentException("pre-update edit changed a participating RootInventory");
            }
            rewritten.add(new TransactionScope(rootChange, scope.planned()));
        }
        // 末尾的新参与者以纳入那一刻的内容为基准; 一个槽位都没写的直接跳过, 免得空写集卡住整笔事务.
        for (int i = this.scopes.size(); i < rootChanges.size(); i++) {
            RootInventoryChange rootChange = rootChanges.get(i);
            if (rootChange.slotChanges().isEmpty()) {
                continue;
            }
            rewritten.add(new TransactionScope(rootChange, this.baselineOf(rootChange.inventory())));
        }
        // 只有整份新结果通过检查后, 才替换当前草稿.
        this.replace(TransactionValidator.validateAndMerge(rewritten));
    }

    // 用通过校验的新写集替换当前草稿.
    private void replace(@NotNull List<TransactionScope> validated) {
        this.scopes = validated;
        this.rootChanges = extractChanges(validated);
        this.plannedStates = plannedStatesOf(validated);
    }

    /**
     * 把按 RootInventory 分组的提交内容整理成事件使用的完整变更列表.
     *
     * @param scopes 要整理的提交内容
     * @return 顺序不变的不可修改列表
     */
    @NotNull
    private static List<RootInventoryChange> extractChanges(@NotNull List<TransactionScope> scopes) {
        List<RootInventoryChange> changes = new ArrayList<>(scopes.size());
        for (int i = 0; i < scopes.size(); i++) {
            changes.add(scopes.get(i).change());
        }
        return List.copyOf(changes);
    }

    /**
     * 抽出每个参与 RootInventory 的规划基准状态, 顺序与提交内容一致.
     *
     * @param scopes 已经通过检查的提交内容
     * @return 与 {@code scopes} 按下标对应的基准状态数组
     */
    @NotNull
    private static ItemStack[][] plannedStatesOf(@NotNull List<TransactionScope> scopes) {
        ItemStack[][] plannedStates = new ItemStack[scopes.size()][];
        for (int i = 0; i < scopes.size(); i++) {
            plannedStates[i] = scopes.get(i).planned();
        }
        return plannedStates;
    }
}
