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
 * <p>已经 {@linkplain SparrowInventory#retired() 退役} 的成员会在读取名单时被顺手剔除.
 */
public final class InventorySequence {
    private final MutableSignal<List<SparrowInventory>> members = Signal.of(List.of()); // 当前成员名单, 每次变化换一份新的不可变列表
    @Nullable private volatile Signal<Long> signal; // 第一次调用 signal() 时创建

    /**
     * 创建包含给定成员的序列, 重复的成员只保留第一次出现.
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
     * 把一个 Inventory 加到名单末尾, 已经在名单里时无操作.
     *
     * @param inventory 要加入的 Inventory
     * @return 原本不在名单里, 这次真的加进去了时返回 true
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
     * 把一个 Inventory 从名单里去掉, 不在名单里时无操作.
     *
     * @param inventory 要去掉的 Inventory
     * @return 原本在名单里, 这次真的去掉了时返回 true
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
     * 返回当前成员名单, 顺带把已经退役的剔出去.
     * <p>剔除会让 {@link #signal()} 失效一次.
     *
     * @return 按加入顺序排列的不可变名单
     */
    @NotNull
    public List<SparrowInventory> inventories() {
        // 一个都没退役时 withoutRetired 原样返回, update 判等相等, 既不换名单也不发失效
        this.members.update(InventorySequence::withoutRetired);
        return this.members.get();
    }

    /**
     * 返回本序列的失效来源: 名单增减了成员, 或任何一个成员的内容变了, 它都失效一次.
     * <p>第一次调用时创建, 之后恒返回同一个实例.
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
                    // 这里读的是名单本身而不是 inventories(), 后者会写回名单, 在 merging 对齐的过程中触发一次它自己的失效回调, 让对齐重入.
                    current = Signals.merging(this.members, SparrowInventory::contentSignal);
                    this.signal = current;
                }
            }
        }
        return current;
    }

    /**
     * 去掉名单里已经退役的 Inventory.
     *
     * @param members 当前名单
     * @return 剔除后的名单, 一个都不用剔时原样返回传入的名单.
     */
    private static List<SparrowInventory> withoutRetired(List<SparrowInventory> members) {
        @Nullable ArrayList<SparrowInventory> kept = null;
        for (int index = 0; index < members.size(); index++) {
            SparrowInventory member = members.get(index);
            if (member.retired()) {
                // 第一个要剔的成员出现时才开始复制, 之前的原样搬过来
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
