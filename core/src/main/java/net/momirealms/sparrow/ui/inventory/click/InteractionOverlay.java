package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bukkit 交互事件留下的现场覆盖.
 * <p>原版先派发事件再执行点击, 事件写进槽位的内容因此是这次点击的输入; Sparrow 先算候选再派发.
 * 覆盖层负责把两者对齐, Bukkit 的写入先攒着而不是直接进写集, 重规划时叠在规划基准之上交给规划器, 使行为与原版对齐.
 * <p>覆盖只改变规划期读到的内容. {@link TransactionScope} 携带的仍是 Inventory 的活数组, 提交前的
 * 并发校验和提交本身都不受影响; 写集记录的 before 也要换回活数组里的真实内容, 否则事件层会看到
 * 容器从来没有过的账.
 * <p>光标是两条路径唯一不同的地方: 点击事件在原版里也跑在 doClick 之前, 它写的光标是这次点击的输入;
 * 拖拽事件却是先算好分配再派发的, 它写的光标本来就是最终值. 覆盖层因此按创建方式区分这两种解释.
 */
final class InteractionOverlay {
    private final boolean cursorIsInput; // 光标写入是这次交互的输入(点击)还是它算完之后的最终值(拖拽)
    // 按 Inventory 身份分组的槽位覆盖. 值为 null 表示"这一格现在是空的", 因此不能用 get 的返回值判断有没有覆盖.
    @Nullable private IdentityHashMap<SparrowInventory, LinkedHashMap<Integer, ItemStack>> slots;
    @Nullable private ItemStack cursor; // 事件写进来的光标, 没写过为 null
    // 叠加结果的缓存, 键是规划基准数组本身: 同一次规划里每份基准至多叠加一次.
    @Nullable private IdentityHashMap<ItemStack[], ItemStack[]> views;

    private InteractionOverlay(boolean cursorIsInput) {
        this.cursorIsInput = cursorIsInput;
    }

    // 点击用的空覆盖, 槽位和光标写的都是这次点击的现场.
    @NotNull
    static InteractionOverlay forClick() {
        return new InteractionOverlay(true);
    }

    // 拖拽用的空覆盖, 槽位写的是现场, 光标写的却是分配算完之后的最终值.
    @NotNull
    static InteractionOverlay forDrag() {
        return new InteractionOverlay(false);
    }

    // 记下事件认为某个 Inventory 槽位现在装的是什么, item 为 null 表示这一格是空的.
    void slot(@NotNull SparrowInventory inventory, int slot, @Nullable ItemStack item) {
        IdentityHashMap<SparrowInventory, LinkedHashMap<Integer, ItemStack>> slots = this.slots;
        if (slots == null) {
            slots = this.slots = new IdentityHashMap<>(2);
        }
        slots.computeIfAbsent(inventory, key -> new LinkedHashMap<>(2)).put(slot, item);
        // 覆盖只会在闸门期间继续长, 一长先前叠好的视图就过期了
        this.views = null;
    }

    // 记下事件写给光标的内容.
    void cursor(@NotNull ItemStack cursor) {
        this.cursor = cursor;
    }

    boolean isEmpty() {
        return this.slots == null && this.cursor == null;
    }

    // 叠加覆盖之后供规划器读的内容, 调用方不得写入; 这个 Inventory 没被覆盖过就原样返回基准, 一次复制都不做.
    @Nullable ItemStack @NotNull [] viewOf(@NotNull PlannedRoot plan) {
        IdentityHashMap<SparrowInventory, LinkedHashMap<Integer, ItemStack>> slots = this.slots;
        if (slots == null) {
            return plan.planned();
        }
        LinkedHashMap<Integer, ItemStack> overrides = slots.get(plan.inventory());
        if (overrides == null) {
            return plan.planned();
        }
        IdentityHashMap<ItemStack[], ItemStack[]> views = this.views;
        if (views == null) {
            views = this.views = new IdentityHashMap<>(2);
        }
        return views.computeIfAbsent(plan.planned(), planned -> overlaid(planned, overrides));
    }

    // 规划器这次该看到的光标: 点击事件写过就用它写的, 其余情况用菜单的实际光标.
    @NotNull
    ItemStack cursorOr(@NotNull ItemStack actual) {
        ItemStack cursor = this.cursor;
        return this.cursorIsInput && cursor != null ? cursor : actual;
    }

    @Nullable
    ItemStack cursor() {
        return this.cursor;
    }

    boolean cursorIsInput() {
        return this.cursorIsInput;
    }

    // 逐条交出攒下的槽位覆盖, 结算阶段凭它判断每一格有没有被新结论消费掉.
    void forEachSlot(@NotNull SlotConsumer consumer) {
        IdentityHashMap<SparrowInventory, LinkedHashMap<Integer, ItemStack>> slots = this.slots;
        if (slots == null) {
            return;
        }
        for (Map.Entry<SparrowInventory, LinkedHashMap<Integer, ItemStack>> group : slots.entrySet()) {
            for (Map.Entry<Integer, ItemStack> override : group.getValue().entrySet()) {
                consumer.accept(group.getKey(), override.getKey(), override.getValue());
            }
        }
    }

    // 在规划基准的副本上盖一层覆盖. 槽位越界的覆盖直接跳过: Inventory 有可能在闸门期间改过大小.
    @Nullable
    private static ItemStack @NotNull [] overlaid(@Nullable ItemStack @NotNull [] planned, @NotNull LinkedHashMap<Integer, ItemStack> overrides) {
        @Nullable ItemStack[] view = planned.clone();
        for (Map.Entry<Integer, ItemStack> override : overrides.entrySet()) {
            int slot = override.getKey();
            if (slot >= 0 && slot < view.length) {
                view[slot] = override.getValue();
            }
        }
        return view;
    }

    @FunctionalInterface
    interface SlotConsumer {

        // item 为 null 表示这一格现在是空的.
        void accept(@NotNull SparrowInventory inventory, int slot, @Nullable ItemStack item);
    }
}
