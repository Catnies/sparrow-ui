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

// Bukkit 事件和 Sparrow 事件共用的同一个写入句柄, 监听器想改这次交互的结果就得经过它.
// 它在两个阶段里的含义不一样. Bukkit 那一轮还挂着覆盖层, 写进来的算这次点击的输入;
// 覆盖层收掉之后写进来的就是提交后的最终值.
@ApiStatus.Internal
public final class InteractionEdits {
    @Nullable private final ClickSemantics.Context context; // 拿它把 Window 槽位翻成 Inventory 槽位; 那种一律丢弃写入的句柄没有上下文, 是 null
    @Nullable private InteractionOverlay overlay;           // 还挂着就把写入攒进临时现场; Bukkit 那一轮跑完置空, 之后写入就按最终值算
    @Nullable private TransactionDraft transaction;         // 写集草稿. 这次点击本来没有写集的话, 等监听器第一次写槽位才建
    @Nullable private InteractionDraft interaction;         // 光标、副手和掉落物的草稿. 同样等监听器第一次写光标才建
    @Nullable private ItemStack expectedCursor;             // 监听器第一次写入时看到的光标, 提交前拿它复核一下没被别人换掉

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

    // 一个只管收下、什么都不落地的句柄. 给测试用, 也给那些在语义引擎外面单独发事件、手上压根没有草稿的调用方用.
    @NotNull
    @ApiStatus.Internal
    public static InteractionEdits discarding() {
        return new InteractionEdits(null, null, null, null);
    }

    /**
     * 把事件写入的光标合并进本次交互.
     * <p>在 Bukkit 点击事件里写的会被当成这次点击的输入, 引擎据此重算一次结论;
     * 在其余阶段写的就是提交后的最终光标.
     *
     * @param cursor 事件写给光标的物品, {@code null} 表示光标为空
     * @return 本次交互存在落点时返回 {@code true}
     */
    public boolean cursor(@Nullable ItemStack cursor) {
        if (this.context == null) return false;
        // 有些候选本来不关心光标, 也照样记一份监听器写入时的基准, 否则提交前无从判断它有没有被人动过.
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
     * <p>在 Bukkit 事件里写的当输入, 之后写的当最终值. 写到一个还没参与本笔事务的 Inventory 上时, 它会被拉进同一笔事务.
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

    // 槽号已经翻译成 Inventory 坐标了, 这一步把它塞进写集草稿.
    private void write(@NotNull SparrowInventory inventory, int slot, @Nullable ItemStack item) {
        TransactionDraft transaction = this.transaction;
        if (transaction == null) {
            transaction = this.transaction = TransactionDraft.empty();
            this.rememberCursor();
        }
        transaction.setAfter(inventory, slot, item);
    }

    // 收掉覆盖层. Bukkit 那一轮到此结束, 后面无论是 Sparrow 事件还是 Pre, 写进来的都当最终值处理.
    void closeOverlay() {
        this.overlay = null;
    }

    // 结算覆盖层. 重算出的新候选会吃掉一部分槽位覆盖, 剩下没人认领的那些按最终值并进同一笔事务, 不能凭空丢掉.
    void settle(@NotNull InteractionOverlay overlay, @Nullable ClickCandidate target) {
        List<TransactionScope> scopes = target == null ? List.of() : target.scopes();
        overlay.forEachSlot((inventory, slot, item) -> {
            if (!writes(scopes, inventory, slot)) {
                this.write(inventory, slot, item);
            }
        });
        // 点击路径上那份光标覆盖已经被重算消费掉了; 拖拽路径上它本来就是最终值, 直接留着.
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

    // 监听器写完之后如果光标又被别人动了, 这里报出来, 免得提交时把那次改动盖掉.
    @Nullable
    ClickCandidate.StaleReason staleCursor() {
        ItemStack expectedCursor = this.expectedCursor;
        ClickSemantics.Context context = this.context;
        if (expectedCursor == null || context == null || ItemUtils.isHandleContentEqual(context.unsafeCursor(), expectedCursor)) {
            return null;
        }
        return ClickCandidate.StaleReason.CURSOR;
    }

    // 只在第一次写入时记. 那一刻的光标才是监听器算最终值时依据的那份, 比候选规划期看到的原值更贴近它的意图.
    private void rememberCursor() {
        ClickSemantics.Context context = this.context;
        if (this.expectedCursor == null && context != null) {
            this.expectedCursor = context.cursor();
        }
    }

    // 这组写集里有没有人已经动过这个 Inventory 槽位.
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
