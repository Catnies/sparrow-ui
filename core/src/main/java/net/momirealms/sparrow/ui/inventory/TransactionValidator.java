package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 把调用方交来的零散槽位修改整理成一份可以安全提交的清单.
 * <p>检查事务至少修改一个槽位、槽号没有越界, 并把属于同一个 ootInventory 的多组修改合在一起.
 * 如果同一个根槽位或同一个真实外部槽位被写了两次, 直接拒绝整笔事务, 避免提交顺序悄悄决定最终结果.
 */
final class TransactionValidator {

    private TransactionValidator() {
    }

    /**
     * 检查全部待提交内容, 并把同一个 RootInventory 的修改合并到一起.
     * <p>返回结果仍按每个 RootInventory 第一次出现的顺序排列.
     *
     * @param scopes 调用方准备提交的各组 RootInventory 修改
     * @return 已合并且确认没有冲突的不可修改列表
     * @throws IllegalArgumentException 当没有可提交的修改、槽号越界、同一修改基于不同旧内容, 或多个修改写到同一真实槽位时
     */
    @NotNull
    static List<TransactionScope> validateAndMerge(@NotNull List<TransactionScope> scopes) {
        // 一笔事务必须实际修改至少一个 RootInventory.
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("transaction requires at least one scope");
        }
        // 每一组都必须包含槽位变化, 且槽号必须属于规划时看到的 Inventory 大小.
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            if (scope.slotChanges().isEmpty()) {
                throw new IllegalArgumentException("transaction scope has no slot changes");
            }
            int size = scope.planned().length;
            for (int j = 0; j < scope.slotChanges().size(); j++) {
                int slot = scope.slotChanges().get(j).slot();
                if (slot < 0 || slot >= size) {
                    throw new IllegalArgumentException("slot " + slot + " is out of bounds for inventory size " + size);
                }
            }
        }

        // 同一个 RootInventory 可能由视图中的不同区域重复提交, 这里合成一组并保留首次出现顺序.
        LinkedHashMap<RootInventory, TransactionScope> mergedByRoot = new LinkedHashMap<>();
        for (int i = 0; i < scopes.size(); i++) {
            TransactionScope scope = scopes.get(i);
            TransactionScope previous = mergedByRoot.get(scope.inventory());
            if (previous == null) {
                mergedByRoot.put(scope.inventory(), scope);
                continue;
            }

            // 两组修改如果基于不同版本的旧内容计算, 就不能安全地拼成同一笔提交.
            if (previous.planned() != scope.planned()) {
                throw new IllegalArgumentException("transaction contains the same inventory with different planned snapshots");
            }
            List<SlotChange> combined = new ArrayList<>(previous.slotChanges());
            combined.addAll(scope.slotChanges());
            mergedByRoot.put(scope.inventory(), new TransactionScope(scope.inventory(), scope.planned(), combined));
        }

        // 同一个 RootInventory 槽位出现两次时无法判断该采用哪个最终值, 因此拒绝整笔事务.
        List<TransactionScope> merged = List.copyOf(mergedByRoot.values());
        for (int i = 0; i < merged.size(); i++) {
            TransactionScope scope = merged.get(i);
            HashSet<Integer> seenSlots = new HashSet<>();
            for (int j = 0; j < scope.slotChanges().size(); j++) {
                if (!seenSlots.add(scope.slotChanges().get(j).slot())) {
                    throw new IllegalArgumentException("transaction contains conflicting slotChanges for slot " + scope.slotChanges().get(j).slot());
                }
            }
        }

        // 不同 RootInventory 仍可能映射到同一个外部槽位, 也必须避免一笔事务把这个真实槽位写两次.
        if (merged.size() > 1) {
            HashSet<SlotKey> seenPhysicalSlots = new HashSet<>();
            for (int i = 0; i < merged.size(); i++) {
                TransactionScope scope = merged.get(i);
                for (int j = 0; j < scope.slotChanges().size(); j++) {
                    int slot = scope.slotChanges().get(j).slot();
                    if (!seenPhysicalSlots.add(scope.inventory().physicalKey(slot))) {
                        throw new IllegalArgumentException("transaction contains conflicting slotChanges for the same physical slot");
                    }
                }
            }
        }
        return merged;
    }
}
