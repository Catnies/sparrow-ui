package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// Bukkit 闸门的槽位写入会成为重规划输入. 点击光标也是输入, 拖拽光标则是最终结果.
final class InteractionOverlay {
    private final boolean cursorIsInput;
    // null 值表示显式空槽, 不能用 Map#get 判断是否存在覆盖.
    @Nullable private IdentityHashMap<SparrowInventory, LinkedHashMap<Integer, ItemStack>> slots;
    @Nullable private ItemStack cursor;
    // 以规划数组身份缓存叠加结果.
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
        // 新覆盖会使所有已缓存视图失效.
        this.views = null;
    }

    // 记下事件写给光标的内容.
    void cursor(@NotNull ItemStack cursor) {
        this.cursor = cursor;
    }

    boolean isEmpty() {
        return this.slots == null && this.cursor == null;
    }

    // <strong>返回值只读</strong>. 没有覆盖时直接返回规划数组.
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

    // 规划器这次该看到的光标. 点击事件写过就用它写的, 其余情况用菜单的实际光标.
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

    // 在规划基准的副本上叠加仍然有效的槽位覆盖.
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
