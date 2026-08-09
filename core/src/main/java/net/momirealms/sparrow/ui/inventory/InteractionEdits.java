package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 交互事件把自己的写入合并进当前候选草稿的句柄.
 * <p>同一次交互中的 Bukkit 事件, Sparrow 事件和 Pre 处理器写的是同一份草稿, 上一个监听器留下的结果
 * 就是下一个看到的内容. 写入落进哪份草稿只由写入目标决定, 与调用者是谁无关: 光标这类容器外副作用
 * 进 {@link InteractionDraft}, 容器内容进 {@link TransactionDraft}.
 * <p>Bukkit 闸门期间是例外: 那时句柄挂着一层 {@link InteractionOverlay}, 写入先攒进覆盖层, 表达的是
 * "这一格现在就是这个值", 由随后的重规划当成输入读走 —— 原版本来就是先派发事件再执行点击. 闸门结束后
 * 句柄换回最终值语义, 覆盖层里没有被新结论消费掉的部分在 {@link #settle} 里追加成最终值.
 * <p>两份草稿都可以等到第一次写入才建: 算不出候选的交互和写集为空的候选照样接受写入, 攒下来的内容
 * 自成一笔事务. 只有这个 Window 槽位背后根本没有 Inventory(Item 槽, 空槽, 冻结槽)时写入才会被丢弃,
 * 此时写入方法返回 {@code false}, 由调用方决定是否强制向客户端重发内容.
 */
@ApiStatus.Internal
public final class InteractionEdits {
    @Nullable private final ClickSemantics.Context context; // 解析 Window 槽位用的交互上下文, 一律丢弃的句柄为 null
    @Nullable private InteractionOverlay overlay;           // 挂着覆盖层时写入先攒进现场, 闸门结束后置空换回最终值语义
    @Nullable private TransactionDraft transaction;         // 写集草稿, 规划期没有写集时等到第一次槽位写入才建
    @Nullable private InteractionDraft interaction;         // 副作用草稿, 没有候选时等到第一次光标写入才建
    @Nullable private ItemStack expectedCursor;             // 第一次写入那一刻的光标, 提交前要复核没有别人换掉它

    InteractionEdits(
            @Nullable ClickSemantics.Context context,
            @Nullable TransactionDraft transaction,
            @Nullable InteractionDraft interaction,
            @Nullable InteractionOverlay overlay
    ) {
        this.context = context;
        this.transaction = transaction;
        this.interaction = interaction;
        this.overlay = overlay;
    }

    // 创建一个一律丢弃写入的 Edits, 供测试与在语义引擎之外单独派发事件, 因而没有任何草稿可用的调用方使用.
    @NotNull
    @ApiStatus.Internal
    public static InteractionEdits discarding() {
        return new InteractionEdits(null, null, null, null);
    }

    /**
     * 把事件写入的光标合并进本次交互.
     * <p>点击的 Bukkit 闸门期间写进来的是光标现在的内容, 由重规划当成输入; 其余位置写进来的都是提交后的
     * 最终值, 与并发检测使用的规划期原值无关. 事务没能提交时这次写入一并作废.
     *
     * @param cursor 事件写给光标的物品, {@code null} 表示光标为空
     * @return 本次交互存在落点时返回 {@code true}
     */
    public boolean cursor(@Nullable ItemStack cursor) {
        if (this.context == null) return false;
        // 无论草稿是不是刚建的都记一次: 候选自己不复核光标(shift, 数字键, 换副手)时, 这是唯一能发现
        // "监听器写了最终值, 又有人直接换掉菜单实际光标"的地方.
        this.rememberCursor();
        ItemStack after = ItemUtils.copyOrEmpty(cursor);
        InteractionOverlay overlay = this.overlay;
        if (overlay != null) {
            overlay.cursor(after);
            return true;
        }
        InteractionDraft interaction = this.interaction;
        if (interaction == null) {
            interaction = this.interaction = InteractionDraft.empty();
        }
        interaction.cursor(after);
        return true;
    }

    /**
     * 把事件写入的槽位内容合并进本次交互.
     * <p>Bukkit 闸门期间写进来的是这一格现在的内容, 由重规划当成输入; 之后写进来的是提交后的最终值,
     * 此时 Window 槽位背后的 Inventory 还没参与本笔事务的会自动纳入, 与原有参与者一起成功或一起回滚.
     * 两种情况都不经过槽级放入规则过滤.
     *
     * @param windowSlot 被写入的 Window 槽位
     * @param item 事件写给该槽位的物品, 空物品表示清空槽位
     * @return 写入已经被接受时返回 {@code true}; Item 槽, 空槽, 冻结槽与玩家侧只读的 Inventory 返回 {@code false}
     */
    public boolean slot(int windowSlot, @Nullable ItemStack item) {
        ClickSemantics.Context context = this.context;
        // 冻结槽在语义上不参与交互; Item 槽与空槽背后根本没有 Inventory 可写.
        if (context == null || context.frozenAt(windowSlot)) {
            return false;
        }
        ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
        // 玩家侧只读的 Inventory 同样拒收: 否则这笔写入并进写集后, 整笔玩家事务会被冻结兜底取消.
        if (link == null || link.inventory().frozen()) {
            return false;
        }
        @Nullable ItemStack written = ItemUtils.nullIfEmpty(item);
        InteractionOverlay overlay = this.overlay;
        if (overlay != null) {
            overlay.slot(link.inventory(), link.slot(), written);
            return true;
        }
        this.write(link.inventory(), link.slot(), written);
        return true;
    }

    // 把一次已经解析到 Inventory 槽位的写入落进写集草稿.
    private void write(@NotNull SparrowInventory inventory, int slot, @Nullable ItemStack item) {
        TransactionDraft transaction = this.transaction;
        if (transaction == null) {
            transaction = this.transaction = TransactionDraft.empty();
            this.rememberCursor();
        }
        transaction.setAfter(inventory, slot, item);
    }

    // 关闭现场覆盖阶段: Bukkit 闸门跑完之后, 后面每一道闸门写进来的都是提交后的最终值.
    void closeOverlay() {
        this.overlay = null;
    }

    // 把闸门留下的现场覆盖结算进本句柄的草稿. 新结论已经把某一格算进写集时, 覆盖只是它的规划输入,
    // 不再重复写一遍; 结论没碰过的格子则是一次独立的改动, 追加成最终值随同一笔事务提交.
    void settle(@NotNull InteractionOverlay overlay, @Nullable ClickCandidate target) {
        List<TransactionScope> scopes = target == null ? List.of() : target.scopes();
        overlay.forEachSlot((inventory, slot, item) -> {
            if (!writes(scopes, inventory, slot)) {
                this.write(inventory, slot, item);
            }
        });
        // 点击事件写的光标是新结论的输入, 结论自己算出了光标就以结论为准, 覆盖已经被消费掉了;
        // 拖拽的分配在派发之前就算好, 事件写的光标本来就是最终值, 一律落地.
        @Nullable ItemStack cursor = overlay.cursor();
        @Nullable ItemStack planned = target == null ? null : target.draft().cursor();
        if (cursor != null && (planned == null || !overlay.cursorIsInput())) {
            this.cursor(cursor);
        }
    }

    /**
     * 返回本次交互攒下的写集草稿.
     *
     * @return 写集草稿; 规划期没有写集, 闸门也没有写过任何槽位时为 {@code null}
     */
    @Nullable
    TransactionDraft transaction() {
        return this.transaction;
    }

    /**
     * 返回本次交互攒下的副作用草稿.
     *
     * @return 副作用草稿; 没有候选, 闸门也没有写过光标时为 {@code null}
     */
    @Nullable
    InteractionDraft interaction() {
        return this.interaction;
    }

    /**
     * 复核光标仍然是第一次写入那一刻的样子.
     * <p>靠它发现有人在监听器写完之后又直接换掉了菜单实际光标: 草稿写的是提交后的最终值, 两者同时生效时
     * 后应用的草稿会悄悄盖掉那次改动. 没有候选的交互和不复核光标的候选都用它兜底.
     *
     * @return 光标已经被换掉时返回 {@link ClickCandidate.StaleReason#CURSOR}, 仍然成立时返回 {@code null}
     */
    @Nullable
    ClickCandidate.StaleReason staleCursor() {
        ItemStack expectedCursor = this.expectedCursor;
        ClickSemantics.Context context = this.context;
        if (expectedCursor == null || context == null || ItemUtils.isContentEqual(expectedCursor, context.cursor())) {
            return null;
        }
        return ClickCandidate.StaleReason.CURSOR;
    }

    // 第一次写入时记下光标, 之后不再更新. 这一刻是监听器算最终值时看到的那一份, 比候选的规划期原值更贴切.
    // cursor() 按契约返回副本, 这里不再多复制一次.
    private void rememberCursor() {
        ClickSemantics.Context context = this.context;
        if (this.expectedCursor == null && context != null) {
            this.expectedCursor = context.cursor();
        }
    }

    // 一组写集里有没有碰过这个 Inventory 槽位.
    private static boolean writes(@NotNull List<TransactionScope> scopes, @NotNull SparrowInventory inventory, int slot) {
        for (int scopeIndex = 0; scopeIndex < scopes.size(); scopeIndex++) {
            TransactionScope scope = scopes.get(scopeIndex);
            if (scope.inventory() != inventory) continue;
            List<SlotChange> changes = scope.slotChanges();
            for (int changeIndex = 0; changeIndex < changes.size(); changeIndex++) {
                if (changes.get(changeIndex).slot() == slot) return true;
            }
        }
        return false;
    }
}
