package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 一笔事务里被整堆搬走的物品, 在开始写之前先认一遍.
 * <p>一条变更的变更后物品, 如果就是本事务从某个外部存储槽位读出来的那个实例, 或者就是菜单交出的那个光标实例,
 * 说明这件物品是整堆搬过来的, 不是新造的. 这时落地要把它的 NMS 句柄接过去, 而不是写一份副本进去,
 * 与原版 doClick 里直接对调指针的做法一致.
 * <p>认定和取句柄都发生在第一次写入之前, 所以两个容器互相对调, 或者三个容器轮转, 都不会因为谁先写而出错.
 * <p>从存储读出的实例内容会跟着存储一起变, 所以每件搬运物品同时记一份规划时的内容副本. 句柄搬不过去时
 * (存储不支持, 或者来源已经对不上), 落地就写这份记下来的内容, 不会把规划没见过的改动顺手带进目标槽.
 * <p>反方向的事情这里也一并认: 外部存储的物品被自己拿着状态数组的 Inventory 收走时, 记下来源那一格
 * (见 {@link #handedOver}), 好让落地阶段知道那一格必须换新实例.
 */
final class LiveTransfers {
    private static final LiveTransfers NONE = new LiveTransfers(null, null);

    private final @Nullable IdentityHashMap<ItemStack, Moved> moves;
    // 规划实例被自己拿着状态数组的 Inventory 收走的那些外部存储槽位变更, 按实例身份记
    private final @Nullable Set<SlotChange> handedOver;

    private LiveTransfers(@Nullable IdentityHashMap<ItemStack, Moved> moves, @Nullable Set<SlotChange> handedOver) {
        this.moves = moves;
        this.handedOver = handedOver;
    }

    /**
     * 在任何落地写入之前, 把整笔事务里的整堆搬运认全.
     * <p>只认两种来源: 一是从外部存储写集里读出来的实例(而且它自己那一格也得有变更, 否则物品根本没离开),
     * 二是交互草稿记下的那个光标实例. 自己拿着状态数组的 Inventory 不参与 —— 它状态数组里的实例还被历史状态版本
     * 和已经派发出去的事件共享着, 把句柄接到容器上, 这些地方的内容会跟着容器一起变.
     *
     * @param scopes 本笔事务的全部写集
     * @param interaction 触发本笔事务的交互副作用草稿, 非玩家交互为 {@code null}
     * @return 认出来的搬运, 一件都没有时为空集
     */
    @NotNull
    static LiveTransfers capture(@NotNull List<TransactionScope> scopes, @Nullable InteractionDraft interaction) {
        @Nullable IdentityHashMap<ItemStack, SourceRef> sources = null;
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            if (!(scope.basis() instanceof SparrowInventory.PlannedRoot.Live)) {
                continue;
            }
            ReferencingInventory owner = (ReferencingInventory) scope.inventory();
            @Nullable ItemStack[] planned = scope.planned();
            List<SlotChange> deltas = scope.slotChanges();
            for (int j = 0; j < deltas.size(); j++) {
                SlotChange delta = deltas.get(j);
                @Nullable ItemStack plannedItem = planned[delta.slot()];
                if (plannedItem == null) {
                    continue;
                }
                if (sources == null) {
                    sources = new IdentityHashMap<>();
                }
                sources.put(plannedItem, new SourceRef(owner, delta));
            }
        }
        @Nullable ItemStack movedCursor = interaction == null ? null : interaction.consumedCursor();
        if (sources == null && movedCursor == null) {
            return NONE;
        }

        @Nullable IdentityHashMap<ItemStack, Moved> moves = null;
        @Nullable Set<SlotChange> handedOver = null;
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            boolean liveReceiver = scope.basis() instanceof SparrowInventory.PlannedRoot.Live;
            List<SlotChange> deltas = scope.slotChanges();
            for (int j = 0; j < deltas.size(); j++) {
                SlotChange delta = deltas.get(j);
                @Nullable ItemStack after = delta.unsafeAfter();
                if (after == null) {
                    continue;
                }
                if (!liveReceiver) {
                    // 收下这件物品的 Inventory 自己拿着状态数组, 这个实例会一直留在它手里.
                    // 来源那一格因此必须换个新实例进去: 再在原来那个上面改数量, 改的就是别人手里的物品了.
                    @Nullable SourceRef source = sources == null ? null : sources.get(after);
                    if (source != null) {
                        if (handedOver == null) {
                            handedOver = Collections.newSetFromMap(new IdentityHashMap<>());
                        }
                        handedOver.add(source.delta());
                    }
                    continue;
                }
                @Nullable Moved moved = null;
                if (after == movedCursor) {
                    // 光标有没有被人换掉, 候选在提交前会复核, 换了就走不到这里; 光标本体随后由交互草稿的最终值覆盖.
                    // 所以句柄可以直接搬走, 内容记的也就是它现在的内容.
                    moved = new Moved(ItemUtils.getItemStackHandle(after), ItemUtils.copyOrNull(after));
                } else if (sources != null) {
                    @Nullable SourceRef source = sources.get(after);
                    if (source != null && source.delta() != delta) {
                        moved = source.owner().captureMove(source.delta());
                    }
                }
                if (moved != null) {
                    if (moves == null) {
                        moves = new IdentityHashMap<>();
                    }
                    moves.put(after, moved);
                }
            }
        }
        return moves == null && handedOver == null ? NONE : new LiveTransfers(moves, handedOver);
    }

    /**
     * 查一个变更后实例是不是整堆搬来的.
     *
     * @param after 写集里的变更后实例
     * @return 搬运记录, 不是搬运时为 {@code null}
     */
    @Nullable
    Moved movedFor(@NotNull ItemStack after) {
        return this.moves == null ? null : this.moves.get(after);
    }

    /**
     * 查一个外部存储槽位的物品是不是已经被别的 Inventory 收走了.
     * <p>收走它的是自己拿着状态数组的 Inventory: 那个实例从此长期留在对方的状态里,
     * 所以这一格必须换个新实例, 既不能因为内容碰巧一样就不写, 也不能在原来那个实例上改数量.
     *
     * @param delta 外部存储侧的槽位变更
     * @return 这一格的物品已经交出去时返回 {@code true}
     */
    boolean handedOver(@NotNull SlotChange delta) {
        return this.handedOver != null && this.handedOver.contains(delta);
    }

    /**
     * 一件认定为整堆搬运的物品.
     *
     * @param handle 来源的 NMS 句柄; {@code null} 表示句柄搬不过去, 落地改成写下面这份内容
     * @param plannedContent 规划时记下的内容副本; 落地写入和更新 lastKnown 都以它为准
     */
    record Moved(@Nullable Object handle, @Nullable ItemStack plannedContent) {
    }

    private record SourceRef(@NotNull ReferencingInventory owner, @NotNull SlotChange delta) {
    }
}
