package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.event.InventoryClickAction;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.transaction.InteractionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 一次点击算出来的完整结论, 包括要改哪些槽位, 以及提交之前必须仍然成立的那些前提.
// 中间每跑过一轮别人的代码, 这些前提都要重新验一遍, 光标被换掉、基准状态被别的写操作顶掉, 这一笔就不能提交了.
record ClickCandidate(
        @NotNull InventoryClickAction action,                    // 候选对应的库存操作
        @Nullable ClickSemantics.LinkedSlot eventTarget,    // Sparrow 点击事件发给哪一格; 拖拽没有单一落点, 是 null
        @NotNull UpdateReason reason,                       // 提交时使用的变更原因
        @NotNull List<TransactionScope> scopes,             // 要改的槽位; 空的表示这一下只动光标之类的 Window 侧状态
        @NotNull List<PlannedRoot> plannedRoots,            // 规划时读过哪些 Inventory 的基准状态, 提交前逐个验它们还没被换掉
        @NotNull ItemStack expectedCursor,                  // 规划时的光标物品
        boolean checkCursor,                                // 是否需要复核光标
        @Nullable ItemStack expectedOffhand,                // 规划时的副手物品
        boolean checkOffhand,                               // 是否需要复核副手
        boolean requireCreative,                            // 是否要求提交时仍处于创造模式
        @NotNull InteractionDraft draft,                    // 容器外面的那些改动, 光标、副手和掉落物的最终值在规划期就填好了
        @NotNull Runnable afterCommit                       // 提交成功后执行的 Window 侧收尾动作
) {

    @NotNull
    static Builder plan(@NotNull InventoryClickAction action, @NotNull UpdateReason reason) {
        return new Builder(action, reason);
    }

    // 覆盖层改的是规划输入, 不该篡改事件里那个 before.
    // 监听器看到的 before 必须是真实的规划基准, 否则它读到的是另一个监听器写的东西, 分不清谁改了什么.
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
                // 只把 before 换回真实基准, after 还是候选算出来的那份.
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

    // 别人的代码跑过之后用这个复核. 先把 ReferencingInventory 重新同步一遍, 期间外部容器可能已经被直接改过, 然后再验全部前提.
    @Nullable
    StaleReason revalidate(ClickSemantics.Context context) {
        for (int rootIndex = 0; rootIndex < this.plannedRoots.size(); rootIndex++) {
            this.plannedRoots.get(rootIndex).inventory().prepareWrite();
        }
        return this.staleReason(context);
    }

    // 只验规划时明确依赖过的那几样, 不去碰外部容器. 没人插手的路径走这条, 省一次读容器.
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

    // 提交成功之后的收尾. 先落地容器外的那些改动, 再做 Window 自己的清理, 顺序和规划期定下的一致.
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

    // 候选的建造器. 什么都不设就表示这一笔没有任何提交前提, 算出来就能交.
    static final class Builder {
        // 库存操作与事件目标
        private final InventoryClickAction action;
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

        private Builder(InventoryClickAction action, UpdateReason reason) {
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

        // 规划期读过的全部基准状态. 提交前逐个看有没有失效, 有一个失效整笔就算冲突.
        @NotNull
        Builder reads(@NotNull List<PlannedRoot> reads) {
            this.reads = reads;
            return this;
        }

        // 调这个就等于要求提交前复核光标没被换掉.
        @NotNull
        Builder checkCursor(@NotNull ItemStack expected) {
            this.expectedCursor = expected;
            return this;
        }

        // 调这个就等于要求提交前复核副手没被换掉; null 表示空副手.
        @NotNull
        Builder checkOffhand(@Nullable ItemStack expected) {
            this.expectedOffhand = expected;
            this.checkOffhand = true;
            return this;
        }

        @NotNull
        Builder requireCreative(boolean requireCreative) {
            this.requireCreative = requireCreative;
            return this;
        }

        @NotNull
        Builder draft(@NotNull InteractionDraft draft) {
            this.draft = draft;
            return this;
        }

        @NotNull
        Builder afterCommit(@NotNull Runnable afterCommit) {
            this.afterCommit = afterCommit;
            return this;
        }

        // 光标和副手各存一份自己的基准, 复核时互不干扰.
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
