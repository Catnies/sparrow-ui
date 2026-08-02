package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.RootInventoryChange;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存 Pre 事件正在讨论的 “最终提交内容”.
 * <p>事务刚进入 Pre 阶段时, 这里保存调用方原本想写入的内容. 一个处理器正常返回后,
 * 它通过 {@code setAfter} 留下的修改会成为新的草稿, 下一个处理器看到的就是这份新结果.
 * 如果处理器抛出异常, {@link TransactionNotification} 不会接纳它留下的修改.
 * <p>Pre 处理器可以改动已经参与事务的 RootInventory 中任意槽位, 但不能增删参与事务的
 * RootInventory. 每个 RootInventory 在事务开始规划时看到的内容也始终不变, 提交时还要用它判断
 * 期间是否插入了其他写操作.
 */
final class TransactionDraft {
    private List<TransactionScope> scopes;         // 按 RootInventory 分组的当前待提交内容
    private List<RootInventoryChange> rootChanges; // 同一份待提交内容, 转成事件可以直接读取的形式
    private final ItemStack[][] plannedStates;     // 事务规划时看到的各个内容数组, 顺序与 rootChanges 一致

    /**
     * 根据已经检查过的事务内容创建 Pre 阶段草稿.
     *
     * @param scopes 每个 RootInventory 原本准备提交的修改
     */
    TransactionDraft(@NotNull List<TransactionScope> scopes) {
        this.scopes = scopes;
        this.rootChanges = extractChanges(scopes);
        this.plannedStates = new ItemStack[scopes.size()][];
        for (int i = 0; i < scopes.size(); i++) {
            this.plannedStates[i] = scopes.get(i).planned();
        }
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
     * 接纳一个正常返回的 Pre 处理器留下的最终值, 让后续处理器和提交阶段继续使用它.
     * <p>处理器只能改动原有 RootInventory 里的槽位. 新结果会再次检查槽位范围和重复写入,
     * 检查失败时不会替换当前草稿.
     *
     * @param rootChanges 处理器执行后的完整变更
     * @throws IllegalArgumentException 当处理器改变了参与事务的 RootInventory, 或产生了非法、冲突的槽位修改时
     */
    void accept(@NotNull List<RootInventoryChange> rootChanges) {
        // 事件仍持有原来的列表, 说明这个处理器没有改动最终值.
        if (rootChanges == this.rootChanges) {
            return;
        }
        // Pre 只能修改现有参与者里的槽位, 不能凭空加入或移除一个 RootInventory.
        if (rootChanges.size() != this.scopes.size()) {
            throw new IllegalArgumentException("pre-update edit changed the participating RootInventory count");
        }

        // 按原顺序核对每个 RootInventory, 并沿用事务规划时看到的内容数组.
        List<TransactionScope> rewritten = new ArrayList<>(this.scopes.size());
        for (int i = 0; i < this.scopes.size(); i++) {
            TransactionScope scope = this.scopes.get(i);
            RootInventoryChange rootChange = rootChanges.get(i);
            if (rootChange.inventory() != scope.inventory()) {
                throw new IllegalArgumentException("pre-update edit changed a participating RootInventory");
            }
            rewritten.add(new TransactionScope(rootChange, scope.planned()));
        }
        // 只有整份新结果通过检查后, 才替换当前草稿.
        List<TransactionScope> validated = TransactionValidator.validateAndMerge(rewritten);
        this.scopes = validated;
        this.rootChanges = extractChanges(validated);
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
}
