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

// 交互事件共用的写入句柄. Bukkit 覆盖阶段记录规划输入, 之后记录提交结果.
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
     * <p>Bukkit 点击闸门中作为重规划输入, 其余阶段作为提交后的最终值.
     *
     * @param cursor 事件写给光标的物品, {@code null} 表示光标为空
     * @return 本次交互存在落点时返回 {@code true}
     */
    public boolean cursor(@Nullable ItemStack cursor) {
        if (this.context == null) return false;
        // 无光标前提的候选也要记录监听器写入时的基准.
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
     * <p>Bukkit 闸门中作为重规划输入, 之后作为最终值; 新 Inventory 会加入同一事务.
     *
     * @param windowSlot 被写入的 Window 槽位
     * @param item 事件写给该槽位的物品, 空物品表示清空槽位
     * @return 写入已经被接受时返回 {@code true}; Item 槽, 空槽, 冻结槽与玩家侧只读的 Inventory 返回 {@code false}
     */
    public boolean slot(int windowSlot, @Nullable ItemStack item) {
        ClickSemantics.Context context = this.context;
        if (context == null || context.frozenAt(windowSlot)) {
            return false;
        }
        ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
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

    // 新候选未消费的槽位覆盖作为独立最终值进入同一事务.
    void settle(@NotNull InteractionOverlay overlay, @Nullable ClickCandidate target) {
        List<TransactionScope> scopes = target == null ? List.of() : target.scopes();
        overlay.forEachSlot((inventory, slot, item) -> {
            if (!writes(scopes, inventory, slot)) {
                this.write(inventory, slot, item);
            }
        });
        // 点击光标覆盖已被重规划消费, 拖拽光标覆盖本身就是最终值.
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

    // 防止最终值覆盖监听器写入后发生的光标变更.
    @Nullable
    ClickCandidate.StaleReason staleCursor() {
        ItemStack expectedCursor = this.expectedCursor;
        ClickSemantics.Context context = this.context;
        if (expectedCursor == null || context == null || ItemUtils.isHandleContentEqual(context.unsafeCursor(), expectedCursor)) {
            return null;
        }
        return ClickCandidate.StaleReason.CURSOR;
    }

    // 只在第一次写入时记下光标. 这一刻是监听器算最终值时看到的那一份, 比候选的规划期原值更贴切.
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
