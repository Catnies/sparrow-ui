package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 一次点击或拖拽形成的精确候选: 已经算好的写集, 加上提交前必须复核的前置条件.
 * <p>候选一旦形成就不再重新规划; 每次经过闸门后都用 {@code plannedRoots} 的基准状态引用,
 * 光标, 副手和游戏模式复核一遍, 任一条件变化就整体作废.
 *
 * @param action 候选对应的 Bukkit 操作
 * @param eventTarget 派发 Sparrow 点击事件的目标槽位, 拖拽候选为 {@code null}
 * @param reason 提交时使用的变更原因
 * @param scopes 候选的写集, 空写集表示只改光标等 Window 侧状态
 * @param plannedRoots 规划所依据的 Inventory 基准状态
 * @param expectedCursor 规划时的光标物品
 * @param checkCursor 是否需要复核光标
 * @param expectedOffhand 规划时的副手物品
 * @param checkOffhand 是否需要复核副手
 * @param requireCreative 是否要求提交时仍处于创造模式
 * @param draft 提交后要应用的容器外副作用, 规划期先填好光标, 副手和掉落物的最终值
 * @param afterCommit 提交成功后执行的 Window 侧收尾动作
 */
record ClickCandidate(
        @NotNull InventoryAction action,
        @Nullable ClickSemantics.LinkedSlot eventTarget,
        @NotNull UpdateReason reason,
        @NotNull List<TransactionScope> scopes,
        @NotNull List<SparrowInventory.PlannedRoot> plannedRoots,
        @NotNull ItemStack expectedCursor,
        boolean checkCursor,
        @Nullable ItemStack expectedOffhand,
        boolean checkOffhand,
        boolean requireCreative,
        @NotNull InteractionDraft draft,
        @NotNull Runnable afterCommit
) {

    // 汇总规划期读过的基准状态, 并对光标与副手做防御复制.
    @NotNull
    static ClickCandidate of(
            InventoryAction action,
            @Nullable ClickSemantics.LinkedSlot eventTarget,
            UpdateReason reason,
            List<TransactionScope> scopes,
            ItemStack expectedCursor,
            boolean checkCursor,
            @Nullable ItemStack expectedOffhand,
            boolean checkOffhand,
            List<SparrowInventory.PlannedRoot> readPlans,
            boolean requireCreative,
            InteractionDraft draft,
            Runnable afterCommit
    ) {
        return new ClickCandidate(
                action,
                eventTarget,
                reason,
                List.copyOf(scopes),
                List.copyOf(readPlans),
                expectedCursor.clone(),
                checkCursor,
                ItemUtils.copyOrNull(expectedOffhand),
                checkOffhand,
                requireCreative,
                draft,
                afterCommit
        );
    }

    // 先把外部容器的变更同步进 Bukkit 内容镜像, 再复核候选是否仍然成立.
    // 规划期的目标列表已经排除源 Inventory 并按身份去重, 每个 Inventory 在这里至多刷新一次.
    @Nullable
    StaleReason revalidate(ClickSemantics.Context context) {
        for (int rootIndex = 0; rootIndex < this.plannedRoots.size(); rootIndex++) {
            this.plannedRoots.get(rootIndex).inventory().prepareWrite();
        }
        return this.staleReason(context);
    }

    // 不触发任何刷新, 只比对规划时记下的光标, 副手, 游戏模式和各 Inventory 的基准状态引用.
    // 说明是哪个前置条件变了; 候选仍然成立时返回 null.
    // 光标和副手只在规划时真的读过它们时才复核: 没读过就不是本次结论的前提, 换掉了也不影响结论.
    @Nullable
    StaleReason staleReason(ClickSemantics.Context context) {
        if (this.checkCursor && !ItemUtils.isContentEqual(this.expectedCursor, context.cursor())) {
            return StaleReason.CURSOR;
        }
        if (this.checkOffhand && !Objects.equals(this.expectedOffhand, ItemUtils.nullIfEmpty(context.offhand()))) {
            return StaleReason.OFFHAND;
        }
        if (this.requireCreative && context.viewer().getGameMode() != GameMode.CREATIVE) {
            return StaleReason.GAME_MODE;
        }
        for (int rootIndex = 0; rootIndex < this.plannedRoots.size(); rootIndex++) {
            if (this.plannedRoots.get(rootIndex).isStale()) {
                return StaleReason.ROOT_STATE;
            }
        }
        return null;
    }

    // 事务提交成功后先落地容器外的副作用, 再做 Window 本地收尾, 顺序与规划期的闭包保持一致.
    void applyAfterCommit(ClickSemantics.Context context) {
        this.draft.apply(context);
        this.afterCommit.run();
    }

    /**
     * 候选作废的原因.
     */
    enum StaleReason {
        CURSOR,     // 菜单实际光标已经不是规划时看到的那一份
        OFFHAND,    // 副手物品已经不是规划时看到的那一份
        GAME_MODE,  // 玩家已经不在候选要求的游戏模式
        ROOT_STATE  // 某个 Inventory 的基准状态被另一笔写操作换掉了
    }
}
