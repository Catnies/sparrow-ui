package net.momirealms.sparrow.ui.inventory.click;

import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// 原版是先发事件再执行点击, 所以 Bukkit 监听器往事件里写的东西天然是这次点击的输入.
// Sparrow 反过来, 先算候选再发事件, 于是监听器那些写入只能先攒在这里当成一层临时现场,
// 等事件跑完再当输入读一遍. 槽位写入和点击光标都属于这种输入.
// 拖拽是唯一的例外, 它的分配在发事件之前就算好了, 监听器写的光标就是最终值, cursorIsInput 记的就是这个区别.
final class InteractionOverlay {
    private final boolean cursorIsInput;
    // 这里的 null 是有意义的值, 表示监听器明确把这一格写空了. 判断有没有覆盖要用 containsKey, 不能拿 get 的结果是不是 null 来判断.
    @Nullable private IdentityHashMap<SparrowInventory, LinkedHashMap<Integer, ItemStack>> slots;
    @Nullable private ItemStack cursor;
    // 叠加结果按规划数组的身份缓存, 同一次规划里反复读同一个 Inventory 不会重复拼数组.
    @Nullable private IdentityHashMap<ItemStack[], ItemStack[]> views;

    private InteractionOverlay(boolean cursorIsInput) {
        this.cursorIsInput = cursorIsInput;
    }

    // 点击用的空覆盖层. 这一路上槽位和光标写进来的都算输入.
    @NotNull
    static InteractionOverlay forClick() {
        return new InteractionOverlay(true);
    }

    // 拖拽用的空覆盖层. 槽位写进来仍然算输入, 光标写进来的却已经是最终值.
    @NotNull
    static InteractionOverlay forDrag() {
        return new InteractionOverlay(false);
    }

    // 记下监听器认为某个 Inventory 槽位现在装着什么. item 传 null 是把这一格写空, 不是撤销覆盖.
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

    // 规划器这一轮该看到的槽位内容. 没人覆盖过就把规划数组原样交出去, 省一次克隆.
    // <strong>返回值只读</strong>.
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

    // 规划器这一轮该看到的光标. 点击路径上监听器写过就用它那份, 其余情况用菜单里真实的光标.
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

    // 把攒下的槽位覆盖逐条交出去. 结算的时候靠它一格一格对, 看哪些已经被新候选吃掉了, 哪些还得自己单独提交.
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

    // 在规划基准的副本上盖一层覆盖. 越界的槽号直接跳过, 监听器可能写了个这个 Inventory 根本没有的位置.
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
