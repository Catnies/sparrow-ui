package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.transaction.InteractionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionDraft;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 交互事件把自己的写入合并进当前候选草稿的句柄.
 * <p>同一次交互中的 Bukkit 事件, Sparrow 事件和 Pre 处理器写的是同一份草稿, 上一个监听器留下的结果就是下一个看到的内容.
 * <p>Bukkit 闸门期间是例外, 那时句柄挂着一层 {@link InteractionOverlay}, 写入攒进覆盖层, 表达的是
 * "这一格现在就是这个值", 由随后的重规划当成输入读走, 原版就是先派发事件再执行点击. 闸门结束后
 * 句柄换回最终值语义, 覆盖层里没有被新结论消费掉的部分在 {@link #settle} 里追加成最终值.
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
        // 无论草稿是不是刚建的都记一次. 候选自己不复核光标(shift, 数字键, 换副手)时,
        // 这是唯一能发现 "监听器写了最终值, 又有人直接换掉菜单实际光标"的地方.
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
        // 玩家侧只读的 Inventory 同样拒收, 这笔写入并进写集之后整笔玩家事务会被冻结兜底取消.
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

    // 关闭现场覆盖阶段. Bukkit 闸门跑完之后, 后面每一道闸门写进来的都是提交后的最终值.
    void closeOverlay() {
        this.overlay = null;
    }

    // 把闸门留下的现场覆盖结算进本句柄的草稿. 新结论已经把某一格算进写集时, 覆盖只是它的规划输入,
    // 到此为止. 结论没碰过的格子则是一次独立的改动, 追加成最终值随同一笔事务提交.
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

    @Nullable
    TransactionDraft transaction() {
        return this.transaction;
    }

    @Nullable
    InteractionDraft interaction() {
        return this.interaction;
    }

    // 复核光标还是第一次写入那一刻的样子, 靠它抓住"监听器写完最终值之后又有人直接换掉菜单光标"
    // 两者同时生效的话, 后应用的草稿会把那次改动悄悄盖掉. 没有候选的交互和不复核光标的候选都拿它兜底.
    @Nullable
    ClickCandidate.StaleReason staleCursor() {
        ItemStack expectedCursor = this.expectedCursor;
        ClickSemantics.Context context = this.context;
        if (expectedCursor == null || context == null || ItemUtils.isContentEqual(expectedCursor, context.cursor())) {
            return null;
        }
        return ClickCandidate.StaleReason.CURSOR;
    }

    // 只在第一次写入时记下光标. 这一刻是监听器算最终值时看到的那一份, 比候选的规划期原值更贴切.
    private void rememberCursor() {
        ClickSemantics.Context context = this.context;
        if (this.expectedCursor == null && context != null) {
            this.expectedCursor = context.cursor(); // 返回副本, 直接存下即可.
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
