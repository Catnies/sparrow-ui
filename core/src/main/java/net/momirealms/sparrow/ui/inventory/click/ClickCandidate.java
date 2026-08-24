package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.transaction.InteractionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 已规划的写集及其提交前置条件, 每道用户代码闸门后都要重新校验.
record ClickCandidate(
        @NotNull InventoryAction action,                    // 候选对应的 Bukkit 操作
        @Nullable ClickSemantics.LinkedSlot eventTarget,    // 派发 Sparrow 点击事件的目标槽位, 拖拽候选为 {@code null}
        @NotNull UpdateReason reason,                       // 提交时使用的变更原因
        @NotNull List<TransactionScope> scopes,             // 候选的写集, 空写集表示只改光标等 Window 侧状态
        @NotNull List<PlannedRoot> plannedRoots,            // 规划所依据的 Inventory 基准状态
        @NotNull ItemStack expectedCursor,                  // 规划时的光标物品
        boolean checkCursor,                                // 是否需要复核光标
        @Nullable ItemStack expectedOffhand,                // 规划时的副手物品
        boolean checkOffhand,                               // 是否需要复核副手
        boolean requireCreative,                            // 是否要求提交时仍处于创造模式
        @NotNull InteractionDraft draft,                    // 提交后要应用的容器外副作用, 规划期先填好光标, 副手和掉落物的最终值
        @NotNull Runnable afterCommit                       // 提交成功后执行的 Window 侧收尾动作
) {

    @NotNull
    static Builder plan(@NotNull InventoryAction action, @NotNull UpdateReason reason) {
        return new Builder(action, reason);
    }

    // 覆盖层只改变规划输入, 事件中的 before 仍应来自真实规划基准.
    @NotNull
    ClickCandidate withRealBefore(@NotNull InteractionOverlay overlay) {
        if (overlay.isEmpty() || this.scopes.isEmpty()) {
            return this;
        }
        List<TransactionScope> rewritten = new ArrayList<>(this.scopes.size());
        for (int scopeIndex = 0; scopeIndex < this.scopes.size(); scopeIndex++) {
            TransactionScope scope = this.scopes.get(scopeIndex);
            @Nullable ItemStack[] planned = scope.planned();
            List<SlotChange> changes = scope.slotChanges();
            List<SlotChange> restored = new ArrayList<>(changes.size());
            for (int changeIndex = 0; changeIndex < changes.size(); changeIndex++) {
                SlotChange change = changes.get(changeIndex);
                // 只换 before 的来源, after 沿用候选算出的内容.
                restored.add(new SlotChange(change.slot(), planned[change.slot()], change.unsafeAfter()));
            }
            rewritten.add(scope.withSlotChanges(restored));
        }
        return new ClickCandidate(
                this.action,
                this.eventTarget,
                this.reason,
                List.copyOf(rewritten),
                this.plannedRoots,
                this.expectedCursor,
                this.checkCursor,
                this.expectedOffhand,
                this.checkOffhand,
                this.requireCreative,
                this.draft,
                this.afterCommit
        );
    }

    // 同步引用存储后复核全部候选前提.
    @Nullable
    StaleReason revalidate(ClickSemantics.Context context) {
        for (int rootIndex = 0; rootIndex < this.plannedRoots.size(); rootIndex++) {
            this.plannedRoots.get(rootIndex).inventory().prepareWrite();
        }
        return this.staleReason(context);
    }

    // 只检查规划路径明确依赖的状态, 不触发刷新.
    @Nullable
    StaleReason staleReason(ClickSemantics.Context context) {
        if (this.checkCursor && !ItemUtils.isHandleContentEqual(context.unsafeCursor(), this.expectedCursor)) {
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

    // 候选作废的原因.
    enum StaleReason {
        CURSOR,     // 菜单实际光标已经不是规划时看到的那一份
        OFFHAND,    // 副手物品已经不是规划时看到的那一份
        GAME_MODE,  // 玩家已经不在候选要求的游戏模式
        ROOT_STATE  // 某个 Inventory 的基准状态被另一笔写操作换掉了
    }

    // 候选的建造器, 什么都不设就是"什么都不复核".
    static final class Builder {
        // Bukkit 操作与事件目标
        private final InventoryAction action;
        private final UpdateReason reason;
        @Nullable private ClickSemantics.LinkedSlot eventTarget;
        // 事务写集与读集
        private List<TransactionScope> scopes = List.of();
        private List<PlannedRoot> reads = List.of();
        // 提交前置条件
        @Nullable private ItemStack expectedCursor;
        @Nullable private ItemStack expectedOffhand;
        private boolean checkOffhand;
        private boolean requireCreative;
        // 提交后的容器外变更
        @Nullable private InteractionDraft draft;
        private Runnable afterCommit = () -> {};

        private Builder(InventoryAction action, UpdateReason reason) {
            this.action = action;
            this.reason = reason;
        }

        @NotNull
        Builder scopes(@NotNull List<TransactionScope> scopes) {
            this.scopes = scopes;
            return this;
        }

        @NotNull
        Builder eventTarget(@NotNull ClickSemantics.LinkedSlot eventTarget) {
            this.eventTarget = eventTarget;
            return this;
        }

        // 规划期读过的全部基准状态, 提交前逐个复核失效.
        @NotNull
        Builder reads(@NotNull List<PlannedRoot> reads) {
            this.reads = reads;
            return this;
        }

        // 记下规划时读到的光标, 并要求提交前复核它没被换掉.
        @NotNull
        Builder checkCursor(@NotNull ItemStack expected) {
            this.expectedCursor = expected;
            return this;
        }

        // 记下规划时读到的副手, 并要求提交前复核它没被换掉; null 表示空副手.
        @NotNull
        Builder checkOffhand(@Nullable ItemStack expected) {
            this.expectedOffhand = expected;
            this.checkOffhand = true;
            return this;
        }

        // 要求提交时玩家仍处于创造模式.
        @NotNull
        Builder requireCreative(boolean requireCreative) {
            this.requireCreative = requireCreative;
            return this;
        }

        // 提交后要应用的容器外副作用.
        @NotNull
        Builder draft(@NotNull InteractionDraft draft) {
            this.draft = draft;
            return this;
        }

        // 提交成功后的 Window 侧收尾动作.
        @NotNull
        Builder afterCommit(@NotNull Runnable afterCommit) {
            this.afterCommit = afterCommit;
            return this;
        }

        // 候选持有独立的光标与副手基准.
        @NotNull
        ClickCandidate build() {
            @Nullable ItemStack expectedCursor = this.expectedCursor;
            return new ClickCandidate(
                    this.action,
                    this.eventTarget,
                    this.reason,
                    List.copyOf(this.scopes),
                    List.copyOf(this.reads),
                    expectedCursor != null ? expectedCursor.clone() : ItemUtils.EMPTY,
                    expectedCursor != null,
                    ItemUtils.copyOrNull(this.expectedOffhand),
                    this.checkOffhand,
                    this.requireCreative,
                    this.draft != null ? this.draft : InteractionDraft.empty(),
                    this.afterCommit
            );
        }
    }
}
