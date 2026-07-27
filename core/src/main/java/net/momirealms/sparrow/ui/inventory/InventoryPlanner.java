package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotDelta;
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

/**
 * 批量操作的纯函数规划器: 在给定快照上产出变更集, 不接触任何锁与事件.
 * <p>快照型库存与视图家族共享同一套算法; simulate 与真实操作共享同一实现,
 * 数量守恒由结构保证. 快照在规划期间不变, 由调用方保证.
 */
final class InventoryPlanner {

    /** 放入规划的产物: 变更集与放不下的余量. */
    record AddPlan(List<SlotDelta> deltas, int remaining) {
    }

    /** 收集与移除规划的产物: 变更集与实际取出的数量. */
    record TakePlan(List<SlotDelta> deltas, int taken) {
    }

    private InventoryPlanner() {
    }

    /**
     * 批量放入的两遍规划: 先把相似且未满的堆填到有效上限, 再按顺序占用空槽.
     */
    @NotNull
    static AddPlan planAdd(@Nullable ItemStack[] snapshot, ItemStack item, SlotOrder order, IntUnaryOperator slotLimit) {
        List<SlotDelta> deltas = new ArrayList<>();
        int remaining = item.getAmount();

        // 第一遍: 合并到相似且未满的堆
        for (int i = 0; i < order.size() && remaining > 0; i++) {
            int slot = order.slotAt(i);
            @Nullable ItemStack current = snapshot[slot];
            if (!ItemUtils.isSimilar(current, item)) {
                continue;
            }
            int space = effectiveMaxStackSize(slotLimit, slot, current) - current.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            deltas.add(new SlotDelta(slot, current, ItemUtils.copyWithAmount(current, current.getAmount() + moved)));
            remaining -= moved;
        }

        // 第二遍: 占用空槽
        for (int i = 0; i < order.size() && remaining > 0; i++) {
            int slot = order.slotAt(i);
            if (snapshot[slot] != null) {
                continue;
            }
            int moved = Math.min(effectiveMaxStackSize(slotLimit, slot, item), remaining);
            if (moved <= 0) {
                continue;
            }
            deltas.add(new SlotDelta(slot, null, ItemUtils.copyWithAmount(item, moved)));
            remaining -= moved;
        }
        return new AddPlan(deltas, remaining);
    }

    /**
     * 批量收集的两遍规划: 先收取非满堆的零头保持满堆完整, 不足再动满堆.
     * 用 touched 防止同一槽被两遍重复收取.
     */
    @NotNull
    static TakePlan planCollect(@Nullable ItemStack[] snapshot, ItemStack template, int upTo, SlotOrder order, @Nullable IntPredicate includedSlot, IntUnaryOperator slotLimit) {
        List<SlotDelta> deltas = new ArrayList<>();
        int taken = 0;
        boolean[] touched = new boolean[snapshot.length];

        for (int pass = 0; pass < 2 && taken < upTo; pass++) {
            boolean wantFullStacks = pass == 1;
            for (int i = 0; i < order.size() && taken < upTo; i++) {
                int slot = order.slotAt(i);
                @Nullable ItemStack current = snapshot[slot];
                if (touched[slot] || current == null || !ItemUtils.isSimilar(current, template)) {
                    continue;
                }
                boolean fullStack = current.getAmount() >= effectiveMaxStackSize(slotLimit, slot, current);
                if (fullStack != wantFullStacks) {
                    continue;
                }
                // 匹配槽只会落入一个 pass, 因而过滤器至多调用一次;调用方可据此认领跨域物理槽.
                if (includedSlot != null && !includedSlot.test(slot)) {
                    continue;
                }
                int take = Math.min(current.getAmount(), upTo - taken);
                deltas.add(new SlotDelta(slot, current, reduced(current, take)));
                touched[slot] = true;
                taken += take;
            }
        }
        return new TakePlan(deltas, taken);
    }

    /**
     * 批量移除的规划: 按给定顺序逐槽把 matcher 命中的物品扣减到目标数量.
     */
    @NotNull
    static TakePlan planRemove(@Nullable ItemStack[] snapshot, Predicate<@NotNull ItemStack> matcher, int upTo, SlotOrder order) {
        List<SlotDelta> deltas = new ArrayList<>();
        int taken = 0;
        for (int i = 0; i < order.size() && taken < upTo; i++) {
            int slot = order.slotAt(i);
            @Nullable ItemStack current = snapshot[slot];
            // matcher 是用户代码, 只允许它接触克隆
            if (current == null || !matcher.test(current.clone())) {
                continue;
            }
            int take = Math.min(current.getAmount(), upTo - taken);
            deltas.add(new SlotDelta(slot, current, reduced(current, take)));
            taken += take;
        }
        return new TakePlan(deltas, taken);
    }

    // 放入类算法的有效上限 = min(槽上限, 物品自身上限)
    private static int effectiveMaxStackSize(IntUnaryOperator slotLimit, int slot, ItemStack item) {
        return Math.min(slotLimit.applyAsInt(slot), item.getMaxStackSize());
    }

    @Nullable
    private static ItemStack reduced(ItemStack current, int take) {
        int left = current.getAmount() - take;
        return left > 0 ? ItemUtils.copyWithAmount(current, left) : null;
    }
}
