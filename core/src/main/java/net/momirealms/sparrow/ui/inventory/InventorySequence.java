package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一组按顺序参与聚合的 Inventory, 成员可以随时增减.
 * <p>名单变化与成员内容变化汇成 {@link #signal()} 失效.
 */
public final class InventorySequence {
    private final MutableSignal<List<SparrowInventory>> members = Signal.of(List.of());
    @Nullable private volatile Signal<Long> signal; // 第一次调用 signal() 时创建

    /**
     * 创建包含给定成员的序列, 重复成员只保留第一次出现.
     *
     * @param inventories 初始成员
     * @return 新建的序列
     */
    @NotNull
    public static InventorySequence of(SparrowInventory @NotNull ... inventories) {
        InventorySequence sequence = new InventorySequence();
        for (int index = 0; index < inventories.length; index++) {
            sequence.add(inventories[index]);
        }
        return sequence;
    }

    /**
     * 将一个 Inventory 加到末尾.
     *
     * @param inventory 要加入的 Inventory
     * @return 成员列表发生变化时返回 true
     */
    public boolean add(@NotNull SparrowInventory inventory) {
        if (this.members.get().contains(inventory)) {
            return false;
        }
        this.members.update(current -> {
            if (current.contains(inventory)) {
                return current;
            }
            ArrayList<SparrowInventory> next = new ArrayList<>(current);
            next.add(inventory);
            return List.copyOf(next);
        });
        return true;
    }

    /**
     * 从序列中移除一个 Inventory.
     *
     * @param inventory 要去掉的 Inventory
     * @return 成员列表发生变化时返回 true
     */
    public boolean remove(@NotNull SparrowInventory inventory) {
        if (!this.members.get().contains(inventory)) {
            return false;
        }
        this.members.update(current -> {
            int index = current.indexOf(inventory);
            if (index < 0) {
                return current;
            }
            ArrayList<SparrowInventory> next = new ArrayList<>(current);
            next.remove(index);
            return List.copyOf(next);
        });
        return true;
    }

    /**
     * 返回当前成员名单, 并移除已经退役的成员.
     * <p>成员被移除时 {@link #signal()} 会失效.
     *
     * @return 按加入顺序排列的不可变名单
     */
    @NotNull
    public List<SparrowInventory> inventories() {
        // 没有退役成员时保留原列表, 避免发出无效失效通知.
        this.members.update(InventorySequence::withoutRetired);
        return this.members.get();
    }

    /**
     * 返回本序列的失效来源. 名单增减了成员, 或任何一个成员的内容变了, 它都失效一次.
     *
     * @return 本序列的失效来源
     */
    @NotNull
    public Signal<Long> signal() {
        Signal<Long> current = this.signal;
        if (current == null) {
            synchronized (this) {
                current = this.signal;
                if (current == null) {
                    // 合并名单本身与当前成员的内容失效信号.
                    current = Signals.merging(this.members, SparrowInventory::contentSignal);
                    this.signal = current;
                }
            }
        }
        return current;
    }

    // 没有退役成员时返回原实例, 让 update 保持静默.
    private static List<SparrowInventory> withoutRetired(List<SparrowInventory> members) {
        @Nullable ArrayList<SparrowInventory> kept = null;
        for (int index = 0; index < members.size(); index++) {
            SparrowInventory member = members.get(index);
            if (member.retired()) {
                // 遇到第一个退役成员时再复制前缀.
                if (kept == null) {
                    kept = new ArrayList<>(members.subList(0, index));
                }
                continue;
            }
            if (kept != null) {
                kept.add(member);
            }
        }
        return kept == null ? members : List.copyOf(kept);
    }
}
