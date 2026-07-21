package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.util.ItemSnapshots;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一次容器同步的不可变发送计划.
 */
@ApiStatus.Internal
public sealed interface SyncPlan permits SyncPlan.None, SyncPlan.Delta, SyncPlan.Full {

    /**
     * 返回此计划发送后客户端应持有的容器版本.
     *
     * @return 协议版本
     */
    @NotNull ContainerRevision revision();

    /**
     * 本地与远端已经一致, 不需要发送任何包.
     *
     * @param revision 当前已提交的协议版本
     */
    record None(@NotNull ContainerRevision revision) implements SyncPlan {
    }

    /**
     * 只发送发生语义变化的槽位和可选光标.
     *
     * @param revision 此次槽位更新要写入的协议版本
     * @param slots 按原始槽位编号排序的变更物品
     * @param carried 可选的变更光标物品
     */
    record Delta(
            @NotNull ContainerRevision revision,
            @NotNull Map<Integer, ItemStack> slots,
            @NotNull Optional<ItemStack> carried
    ) implements SyncPlan {

        public Delta {
            if (slots.isEmpty()) {
                throw new IllegalArgumentException("delta requires at least one slot to carry its state id");
            }
            LinkedHashMap<Integer, ItemStack> copies = new LinkedHashMap<>();
            for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
                copies.put(entry.getKey(), ItemSnapshots.copyOrEmpty(entry.getValue()));
            }
            slots = Collections.unmodifiableMap(copies);
            carried = carried.map(ItemSnapshots::copyOrEmpty);
        }
    }

    /**
     * 发送完整槽位与光标状态.
     *
     * @param revision 此次完整更新要写入的协议版本
     * @param slots 按原始槽位编号排列的全部物品
     * @param carried 光标物品
     */
    record Full(
            @NotNull ContainerRevision revision,
            @NotNull List<ItemStack> slots,
            @NotNull ItemStack carried
    ) implements SyncPlan {

        public Full {
            slots = new ContainerSnapshot(slots, carried, revision).items();
            carried = ItemSnapshots.copyOrEmpty(carried);
        }
    }
}
