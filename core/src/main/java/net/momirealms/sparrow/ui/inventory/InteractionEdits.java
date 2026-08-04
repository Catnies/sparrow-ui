package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 交互事件把自己的写入合并进当前候选草稿的句柄.
 * <p>同一次交互中的 Bukkit 事件, Sparrow 事件和 Pre 处理器写的是同一份草稿, 上一个监听器留下的结果
 * 就是下一个看到的内容. 写入落进哪份草稿只由写入目标决定, 与调用者是谁无关: 光标这类容器外副作用
 * 进 {@link InteractionDraft}, 容器内容进 {@link TransactionDraft}.
 * <p>两份草稿都可以等到第一次写入才建: 算不出候选的交互和写集为空的候选照样接受写入, 攒下来的内容
 * 自成一笔事务. 只有这个 Window 槽位背后根本没有 Inventory(Item 槽, 空槽, 冻结槽)时写入才会被丢弃,
 * 此时写入方法返回 {@code false}, 由调用方决定是否强制向客户端重发内容.
 */
@ApiStatus.Internal
public final class InteractionEdits {
    @Nullable private final ClickSemantics.Context context; // 解析 Window 槽位用的交互上下文, 一律丢弃的句柄为 null
    @Nullable private TransactionDraft transaction;         // 写集草稿, 规划期没有写集时等到第一次槽位写入才建
    @Nullable private InteractionDraft interaction;         // 副作用草稿, 没有候选时等到第一次光标写入才建
    @Nullable private ItemStack expectedCursor;             // 懒建草稿那一刻的光标, 提交前要复核没有别人换掉它
    private final Supplier<String> describe;                // 报警时用来指认这次交互
    @Nullable private List<SlotWrite> slotWrites;           // 事件写过的根坐标, 候选作废后据此判断能不能搬到新候选上
    @Nullable private ItemStack cursorWrite;                // 事件写过的光标最终值, 没写过为 null
    private boolean warned;                                 // 已经就丢弃写入报过一次

    InteractionEdits(
            @Nullable ClickSemantics.Context context,
            @Nullable TransactionDraft transaction,
            @Nullable InteractionDraft interaction,
            @NotNull Supplier<String> describe
    ) {
        this.context = context;
        this.transaction = transaction;
        this.interaction = interaction;
        this.describe = describe;
    }

    /**
     * 创建一个一律丢弃写入的句柄, 供在语义引擎之外单独派发事件, 因而没有任何草稿可用的调用方使用.
     *
     * @param describe 报警时用来指认这次交互
     * @return 只会丢弃写入的句柄
     */
    @NotNull
    public static InteractionEdits discarding(@NotNull Supplier<String> describe) {
        return new InteractionEdits(null, null, null, describe);
    }

    /**
     * 把事件写入的光标合并进本次交互的副作用草稿.
     * <p>写进来的是提交后的最终值, 与并发检测使用的规划期原值无关. 事务没能提交时这次写入一并作废.
     *
     * @param cursor 事件希望提交后留在光标上的物品, {@code null} 表示提交后光标为空
     * @return 本次交互存在落点时返回 {@code true}
     */
    public boolean cursor(@Nullable ItemStack cursor) {
        if (this.context == null) {
            return this.discarded("这次交互没有落点, 对光标的写入无处可去");
        }
        InteractionDraft interaction = this.interaction;
        if (interaction == null) {
            interaction = this.interaction = InteractionDraft.empty();
        }
        // 无论草稿是不是刚建的都记一次: 候选自己不复核光标(shift, 数字键, 换副手)时, 这是唯一能发现
        // "监听器写了最终值, 又有人直接换掉菜单实际光标"的地方.
        this.rememberCursor();
        ItemStack after = ItemUtils.copyOrEmpty(cursor);
        interaction.cursor(after);
        this.cursorWrite = after;
        return true;
    }

    /**
     * 把事件写入的槽位内容合并进本次事务的写集.
     * <p>Window 槽位背后的 Inventory 还没参与本笔事务时自动纳入, 之后与原有参与者一起成功或一起回滚.
     * 写入不经过槽级放入规则过滤.
     *
     * @param windowSlot 被写入的 Window 槽位
     * @param item 事件希望提交后留在该槽位的物品, 空物品表示清空槽位
     * @return 写入已经合并进事务时返回 {@code true}; Item 槽, 空槽与冻结槽返回 {@code false}
     */
    public boolean slot(int windowSlot, @Nullable ItemStack item) {
        ClickSemantics.Context context = this.context;
        if (context == null) {
            return this.discarded("这次交互没有落点, windowSlot " + windowSlot + " 的写入无处可去");
        }
        // 冻结槽在语义上不参与交互; Item 槽与空槽背后根本没有 Inventory 可写.
        if (context.frozenAt(windowSlot)) {
            return this.discarded("windowSlot " + windowSlot + " 是冻结槽, 不参与交互");
        }
        ClickSemantics.LinkedSlot link = context.linkAt(windowSlot);
        if (link == null) {
            return this.discarded("windowSlot " + windowSlot + " 背后没有 Inventory(Item 槽或空槽)");
        }
        InventoryTopology topology = link.inventory().topology();
        this.write(topology.rootOf(link.slot()), topology.rootSlotOf(link.slot()), ItemUtils.nullIfEmpty(item));
        return true;
    }

    // 把一次已经解析到根坐标的写入落进写集草稿, 同时记进写入日志.
    private void write(@NotNull RootInventory root, int rootSlot, @Nullable ItemStack item) {
        TransactionDraft transaction = this.transaction;
        if (transaction == null) {
            transaction = this.transaction = TransactionDraft.empty();
            this.rememberCursor();
        }
        transaction.setAfter(root, rootSlot, item);
        List<SlotWrite> slotWrites = this.slotWrites;
        if (slotWrites == null) {
            slotWrites = this.slotWrites = new ArrayList<>(2);
        }
        slotWrites.add(new SlotWrite(root, rootSlot, item));
    }

    // 事件留下的写入能不能原样搬到重规划出来的候选上. 写入表达的是相对上一份现场算出的最终值,
    // 只要落点不与新候选相撞, 两边就是各写各的, 搬过去仍然成立.
    boolean replayableOnto(@Nullable ClickCandidate replanned) {
        // 光标被人直接换掉过就搬不了: 写进来的值是相对换掉之前那一份算出的. 新候选自己也写光标时同样搬不了.
        if (this.cursorWrite != null
                && (this.staleCursor() != null || (replanned != null && replanned.draft().cursor() != null))) {
            return false;
        }
        List<SlotWrite> slotWrites = this.slotWrites;
        if (slotWrites == null || replanned == null) {
            return true;
        }
        for (int writeIndex = 0; writeIndex < slotWrites.size(); writeIndex++) {
            SlotWrite write = slotWrites.get(writeIndex);
            if (writes(replanned.scopes(), write.root(), write.rootSlot())) {
                return false;
            }
        }
        return true;
    }

    // 把事件留下的写入原样搬到另一份句柄上, 只在落点确认不相撞之后调用.
    void replayInto(@NotNull InteractionEdits target) {
        List<SlotWrite> slotWrites = this.slotWrites;
        if (slotWrites != null) {
            for (int writeIndex = 0; writeIndex < slotWrites.size(); writeIndex++) {
                SlotWrite write = slotWrites.get(writeIndex);
                target.write(write.root(), write.rootSlot(), write.item());
            }
        }
        if (this.cursorWrite != null) {
            target.cursor(this.cursorWrite);
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
        if (expectedCursor == null || context == null || expectedCursor.equals(context.cursor())) {
            return null;
        }
        return ClickCandidate.StaleReason.CURSOR;
    }

    // 第一次写入时记下光标, 之后不再更新. 这一刻是监听器算最终值时看到的那一份, 比候选的规划期原值更贴切.
    private void rememberCursor() {
        ClickSemantics.Context context = this.context;
        if (this.expectedCursor == null && context != null) {
            this.expectedCursor = context.cursor().clone();
        }
    }

    // 报告一次没有落点的写入并返回 false. "我改了但什么都没发生"最常见的来源, 每次交互至多报一条.
    private boolean discarded(@NotNull String cause) {
        if (!this.warned) {
            this.warned = true;
            SparrowUI.getInstance().warn(this.describe.get() + ": 事件写入被丢弃, " + cause
                    + ". 客户端会收到一次全量重发以纠正显示; 本次交互后续的同类写入不再重复告警.");
        }
        return false;
    }

    // 一组写集里有没有碰过这个根坐标.
    private static boolean writes(@NotNull List<TransactionScope> scopes, @NotNull RootInventory root, int rootSlot) {
        for (int scopeIndex = 0; scopeIndex < scopes.size(); scopeIndex++) {
            TransactionScope scope = scopes.get(scopeIndex);
            if (scope.inventory() != root) continue;
            List<SlotChange> changes = scope.slotChanges();
            for (int changeIndex = 0; changeIndex < changes.size(); changeIndex++) {
                if (changes.get(changeIndex).slot() == rootSlot) return true;
            }
        }
        return false;
    }

    // 事件写过的一个根坐标及其最终值.
    private record SlotWrite(@NotNull RootInventory root, int rootSlot, @Nullable ItemStack item) {
    }
}
