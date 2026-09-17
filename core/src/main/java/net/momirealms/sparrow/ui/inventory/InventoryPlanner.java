package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

@ApiStatus.Internal
public final class InventoryPlanner {

    /**
     * 规划一次单槽放入, 空槽看有效上限, 相似堆看剩余空间, 不相似一个不接纳.
     *
     * @param current 该槽当前内容, 空槽为 {@code null}
     * @param input 要放入的物品
     * @param slot 槽位序号
     * @param slotLimit 各槽位的堆叠上限
     * @param allowed 候选变更过滤器, null 表示放行
     * @return 放入方案与放不下的余量; 一个都放不进时方案为空
     */
    @NotNull
    static AddPlan planPut(@Nullable ItemStack current, ItemStack input, int slot, IntUnaryOperator slotLimit, @Nullable Predicate<SlotChange> allowed) {
        int amount = input.getAmount();
        int space;
        if (current == null) {
            space = effectiveMaxStackSize(slotLimit, slot, input);
        } else if (ItemUtils.isSimilar(current, input)) {
            space = effectiveMaxStackSize(slotLimit, slot, current) - current.getAmount();
        } else {
            return new AddPlan(List.of(), amount);
        }
        int moved = Math.clamp(space, 0, amount);
        if (moved == 0) {
            return new AddPlan(List.of(), amount);
        }
        ItemStack after = current != null ? current.clone() : input.clone();
        after.setAmount((current != null ? current.getAmount() : 0) + moved);
        SlotChange delta = new SlotChange(slot, current, after);
        return allowed == null || allowed.test(delta) ? new AddPlan(List.of(delta), amount - moved) : new AddPlan(List.of(), amount);
    }

    /**
     * 规划一次数量增减, 减少时最低到 0, 增加时最高到有效堆叠上限.
     *
     * @param current 该槽当前内容, 空槽为 {@code null}
     * @param slot 槽位序号
     * @param change 数量变化, 正数为增加, 负数为减少
     * @param slotLimit 各槽位的堆叠上限
     * @return 槽位变更; 无事可做时为 {@code null}
     */
    @Nullable
    static SlotChange planAmountChange(@Nullable ItemStack current, int slot, int change, IntUnaryOperator slotLimit) {
        if (current == null || change == 0) {
            return null;
        }
        // 上限只在加的时候管. 一堆本来就超了上限的东西还是要允许往下减, 不然它永远清不掉.
        long desired = (long) current.getAmount() + change;
        int target;
        if (change < 0) {
            target = (int) Math.max(0, desired);
        } else {
            int cap = effectiveMaxStackSize(slotLimit, slot, current);
            if (current.getAmount() >= cap) {
                return null;
            }
            target = (int) Math.min(desired, cap);
        }
        if (target == current.getAmount()) {
            return null;
        }
        return new SlotChange(slot, current, target > 0 ? ItemUtils.copyWithAmount(current, target) : null);
    }

    /**
     * 规划一次批量放入. 按给定顺序先走一遍, 把物品合并进相似且没堆满的堆,
     * 还有剩余再走第二遍, 按同样的顺序占用空槽.
     * 每个槽最多放多少取 min(槽位上限, 物品自身上限), 上限报 0 的槽(比如被调用方禁用的槽)自然被跳过.
     *
     * @param snapshot 供规划算法读取的当前 Inventory 内容, 空槽为 {@code null}
     * @param item 要放入的物品
     * @param order 槽位遍历顺序
     * @param slotLimit 各槽位的堆叠上限
     * @param allowed 候选变更过滤器, null 表示放行
     * @return 放入方案与放不下的余量
     */
    @NotNull
    public static AddPlan planAdd(@Nullable ItemStack[] snapshot, ItemStack item, SlotOrder order, IntUnaryOperator slotLimit, @Nullable Predicate<SlotChange> allowed) {
        List<SlotChange> deltas = new ArrayList<>();
        Object itemHandle = ItemUtils.getItemStackHandle(item);
        int remaining = item.getAmount();

        // 合并到相似且未满的堆
        for (int i = 0; i < order.size() && remaining > 0; i++) {
            int slot = order.slotAt(i);
            @Nullable ItemStack current = snapshot[slot];
            if (!ItemUtils.isSimilarToHandle(current, itemHandle)) {
                continue;
            }
            int space = effectiveMaxStackSize(slotLimit, slot, current) - current.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            SlotChange delta = new SlotChange(slot, current, ItemUtils.copyWithAmount(current, current.getAmount() + moved));
            if (allowed != null && !allowed.test(delta)) {
                continue;
            }
            deltas.add(delta);
            remaining -= moved;
        }

        // 占用空槽
        for (int i = 0; i < order.size() && remaining > 0; i++) {
            int slot = order.slotAt(i);
            if (snapshot[slot] != null) {
                continue;
            }
            int capacity = effectiveMaxStackSize(slotLimit, slot, item);
            if (capacity <= 0) {
                continue;
            }
            int moved = Math.min(capacity, remaining);
            SlotChange delta = new SlotChange(slot, null, ItemUtils.copyWithAmount(item, moved));
            if (allowed != null && !allowed.test(delta)) {
                continue;
            }
            deltas.add(delta);
            remaining -= moved;
        }
        return new AddPlan(deltas, remaining);
    }

    /**
     * 规划一次批量移除. 按给定顺序逐槽检查, matcher 看中的物品就扣掉,
     * 直到凑够数量或者翻完所有槽.
     *
     * @param snapshot 供规划算法读取的当前 Inventory 内容, 空槽为 {@code null}
     * @param matcher 判断某个物品该不该移除; 它是调用方代码, 拿到的是零拷贝的内部引用
     * @param upTo 最多移除的数量
     * @param order 槽位遍历顺序
     * @param allowed 候选变更过滤器, null 表示放行
     * @return 移除方案与实际能移除的数量
     */
    @NotNull
    static TakePlan planRemove(@Nullable ItemStack[] snapshot, Predicate<@NotNull ItemStack> matcher, int upTo, SlotOrder order, @Nullable Predicate<SlotChange> allowed) {
        List<SlotChange> deltas = new ArrayList<>();
        int taken = 0;
        for (int i = 0; i < order.size() && taken < upTo; i++) {
            int slot = order.slotAt(i);
            @Nullable ItemStack current = snapshot[slot];
            if (current == null || !matcher.test(current)) {
                continue;
            }
            int take = Math.min(current.getAmount(), upTo - taken);
            SlotChange delta = new SlotChange(slot, current, reduced(current, take));
            if (allowed != null && !allowed.test(delta)) {
                continue;
            }
            deltas.add(delta);
            taken += take;
        }
        return new TakePlan(deltas, taken);
    }

    /**
     * 规划一次批量收集, 按给定顺序先收没堆满的"零头"(让满堆保持完整), 凑不够再动满堆.
     *
     * @param snapshot 供规划算法读取的当前 Inventory 内容, 空槽为 {@code null}
     * @param template 物品样板, 只用来判断"像不像", 它自己的数量不影响结果
     * @param upTo 最多收集的数量
     * @param order 槽位遍历顺序
     * @param allowed 候选变更过滤器, 返回 false 时跳过该槽; null 表示放行
     * @param slotLimit 各槽位的堆叠上限, 用来判断一个堆满没满
     * @return 收集方案与实际能收到的数量
     */
    @NotNull
    public static TakePlan planCollect(@Nullable ItemStack[] snapshot, ItemStack template, int upTo, SlotOrder order, @Nullable Predicate<SlotChange> allowed, IntUnaryOperator slotLimit) {
        List<SlotChange> deltas = new ArrayList<>();
        int taken = 0;
        Object templateHandle = ItemUtils.getItemStackHandle(template);
        boolean[] touched = new boolean[snapshot.length];

        // 走两遍, 第一遍只收没堆满的零头, 第二遍才动满堆, 这样满堆能尽量留着不拆.
        // touched 记住谁已经被收过了, 一格只许进一遍, 顺带保证过滤器对每格最多问一次.
        for (int pass = 0; pass < 2 && taken < upTo; pass++) {
            boolean wantFullStacks = pass == 1;
            for (int i = 0; i < order.size() && taken < upTo; i++) {
                int slot = order.slotAt(i);
                @Nullable ItemStack current = snapshot[slot];
                if (touched[slot] || !ItemUtils.isSimilarToHandle(current, templateHandle)) {
                    continue;
                }
                boolean fullStack = current.getAmount() >= effectiveMaxStackSize(slotLimit, slot, current);
                if (fullStack != wantFullStacks) {
                    continue;
                }
                int take = Math.min(current.getAmount(), upTo - taken);
                SlotChange delta = new SlotChange(slot, current, reduced(current, take));
                if (allowed != null && !allowed.test(delta)) {
                    continue;
                }
                deltas.add(delta);
                touched[slot] = true;
                taken += take;
            }
        }
        return new TakePlan(deltas, taken);
    }

    // 槽位上限和物品自身上限取小的那个, 才是这件东西在这一格真正放得下多少.
    private static int effectiveMaxStackSize(IntUnaryOperator slotLimit, int slot, ItemStack item) {
        return Math.min(slotLimit.applyAsInt(slot), item.getMaxStackSize());
    }

    // 取光了就是 null, 不留数量为 0 的空壳.
    @Nullable
    private static ItemStack reduced(ItemStack current, int take) {
        int left = current.getAmount() - take;
        return left > 0 ? ItemUtils.copyWithAmount(current, left) : null;
    }

    // deltas 是要写哪些格, remaining 是算完之后还剩多少件放不下.
    public record AddPlan(List<SlotChange> deltas, int remaining) {
    }

    // deltas 是要写哪些格, taken 是实际能取出多少件.
    public record TakePlan(List<SlotChange> deltas, int taken) {
    }
}
