package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

final class InventoryPlanner {

    private InventoryPlanner() {
    }

    /**
     * 规划一次批量放入: 按给定顺序先走一遍, 把物品合并进相似且没堆满的堆;
     * 还有剩余再走第二遍, 按同样的顺序占用空槽.
     * 每个槽最多放多少取 min(槽位上限, 物品自身上限), 上限报 0 的槽(比如被调用方禁用的槽)自然被跳过.
     *
     * @param snapshot 供规划算法读取的当前 Inventory 内容, 空槽为 {@code null}
     * @param item 要放入的物品
     * @param order 槽位遍历顺序
     * @param slotLimit 各槽位的堆叠上限
     * @param includedSlot 槽位过滤器, 只会对结构上能接收物品的槽位调用
     * @return 放入方案与放不下的余量
     */
    @NotNull
    static AddPlan planAdd(@Nullable ItemStack[] snapshot, ItemStack item, SlotOrder order, IntUnaryOperator slotLimit, IntPredicate includedSlot) {
        List<SlotChange> deltas = new ArrayList<>();
        int remaining = item.getAmount();

        // 第一遍: 合并到相似且未满的堆
        for (int i = 0; i < order.size() && remaining > 0; i++) {
            int slot = order.slotAt(i);
            @Nullable ItemStack current = snapshot[slot];
            if (!ItemUtils.isSimilar(current, item)) {
                continue;
            }
            int space = effectiveMaxStackSize(slotLimit, slot, current) - current.getAmount();
            if (space <= 0 || !includedSlot.test(slot)) {
                continue;
            }
            int moved = Math.min(space, remaining);
            deltas.add(new SlotChange(slot, current, ItemUtils.copyWithAmount(current, current.getAmount() + moved)));
            remaining -= moved;
        }

        // 第二遍: 占用空槽
        for (int i = 0; i < order.size() && remaining > 0; i++) {
            int slot = order.slotAt(i);
            if (snapshot[slot] != null) {
                continue;
            }
            int capacity = effectiveMaxStackSize(slotLimit, slot, item);
            if (capacity <= 0 || !includedSlot.test(slot)) {
                continue;
            }
            int moved = Math.min(capacity, remaining);
            deltas.add(new SlotChange(slot, null, ItemUtils.copyWithAmount(item, moved)));
            remaining -= moved;
        }
        return new AddPlan(deltas, remaining);
    }

    /**
     * 规划一次批量移除: 按给定顺序逐槽检查, matcher 看中的物品就扣掉,
     * 直到凑够数量或者翻完所有槽.
     *
     * @param snapshot 供规划算法读取的当前 Inventory 内容, 空槽为 {@code null}
     * @param matcher 判断某个物品该不该移除; 它是调用方代码, 只许接触物品副本
     * @param upTo     最多移除的数量
     * @param order    槽位遍历顺序
     * @return 移除方案与实际能移除的数量
     */
    @NotNull
    static TakePlan planRemove(@Nullable ItemStack[] snapshot, Predicate<@NotNull ItemStack> matcher, int upTo, SlotOrder order) {
        List<SlotChange> deltas = new ArrayList<>();
        int taken = 0;
        for (int i = 0; i < order.size() && taken < upTo; i++) {
            int slot = order.slotAt(i);
            @Nullable ItemStack current = snapshot[slot];
            // matcher 是用户代码, 只允许它接触物品副本
            if (current == null || !matcher.test(current.clone())) {
                continue;
            }
            int take = Math.min(current.getAmount(), upTo - taken);
            deltas.add(new SlotChange(slot, current, reduced(current, take)));
            taken += take;
        }
        return new TakePlan(deltas, taken);
    }

    /**
     * 规划一次批量收集: 按给定顺序先收没堆满的"零头"(让满堆保持完整), 凑不够再动满堆.
     * <p>touched 标记保证同一个槽只会落入其中一遍, 因此 includedSlot 过滤器对每个槽
     * 最多被调用一次.
     *
     * @param snapshot 供规划算法读取的当前 Inventory 内容, 空槽为 {@code null}
     * @param template 物品样板, 只用来判断"像不像", 它自己的数量不影响结果
     * @param upTo 最多收集的数量
     * @param order 槽位遍历顺序
     * @param includedSlot 槽位过滤器, 返回 {@code false} 的槽不参与; {@code null} 表示不过滤
     * @param slotLimit 各槽位的堆叠上限, 用来判断一个堆满没满
     * @return 收集方案与实际能收到的数量
     */
    @NotNull
    static TakePlan planCollect(@Nullable ItemStack[] snapshot, ItemStack template, int upTo, SlotOrder order, @Nullable IntPredicate includedSlot, IntUnaryOperator slotLimit) {
        List<SlotChange> deltas = new ArrayList<>();
        int taken = 0;
        boolean[] touched = new boolean[snapshot.length];

        for (int pass = 0; pass < 2 && taken < upTo; pass++) {
            boolean wantFullStacks = pass == 1;
            for (int i = 0; i < order.size() && taken < upTo; i++) {
                int slot = order.slotAt(i);
                @Nullable ItemStack current = snapshot[slot];
                if (touched[slot] || !ItemUtils.isSimilar(current, template)) {
                    continue;
                }
                boolean fullStack = current.getAmount() >= effectiveMaxStackSize(slotLimit, slot, current);
                if (fullStack != wantFullStacks) {
                    continue;
                }
                // 匹配槽只会落入一个 pass, 因而过滤器至多调用一次; 调用方可据此认领跨 Inventory 的同一 SlotKey.
                if (includedSlot != null && !includedSlot.test(slot)) {
                    continue;
                }
                int take = Math.min(current.getAmount(), upTo - taken);
                deltas.add(new SlotChange(slot, current, reduced(current, take)));
                touched[slot] = true;
                taken += take;
            }
        }
        return new TakePlan(deltas, taken);
    }

    // 计算这个槽对这个物品真正生效的堆叠上限: 槽位上限与物品自身上限取小.
    private static int effectiveMaxStackSize(IntUnaryOperator slotLimit, int slot, ItemStack item) {
        return Math.min(slotLimit.applyAsInt(slot), item.getMaxStackSize());
    }

    // 计算从一个堆里取走若干之后槽位剩下的内容, 取光了就是空槽({@code null}).
    @Nullable
    private static ItemStack reduced(ItemStack current, int take) {
        int left = current.getAmount() - take;
        return left > 0 ? ItemUtils.copyWithAmount(current, left) : null;
    }

    /**
     * 放入规划的结果.
     *
     * @param deltas 每个要改动的槽的变更
     * @param remaining 规划完仍然放不下的数量
     */
    record AddPlan(List<SlotChange> deltas, int remaining) {
    }

    /**
     * 收集与移除规划的结果.
     *
     * @param deltas 每个要改动的槽的变更
     * @param taken 实际能取出的总数量
     */
    record TakePlan(List<SlotChange> deltas, int taken) {
    }
}
