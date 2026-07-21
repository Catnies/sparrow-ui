package net.momirealms.sparrow.ui.internal.menu;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 比较权威容器状态和最后一次已提交的远端快照.
 */
@ApiStatus.Internal
public final class ContainerSynchronizer {

    private ContainerSynchronizer() {
    }

    /**
     * 计算将客户端远端快照收敛到本地权威快照所需的最小安全计划.
     *
     * <p>不同会话、槽位数量变化或强制刷新时必须发送完整状态. 只有槽位变更时可用
     * {@link SyncPlan.Delta}; 单独变更光标仍使用完整状态, 以便同时推进容器 state id.</p>
     *
     * @param local 当前权威快照
     * @param remote 最后一次成功发送的远端快照, 可为空
     * @param forceFull 是否强制完整同步
     * @return 不发送、增量发送或完整发送计划
     */
    public static @NotNull SyncPlan reconcile(
            @NotNull ContainerSnapshot local,
            @Nullable ContainerSnapshot remote,
            boolean forceFull
    ) {
        if (forceFull || remote == null || !sameSession(local, remote) || local.size() != remote.size()) {
            return new SyncPlan.Full(local.revision(), local.items(), local.carried());
        }

        // 对同一会话逐槽比较, 只保留发生语义变化的物品.
        Map<Integer, ItemStack> changed = new LinkedHashMap<>();
        for (int slot = 0; slot < local.size(); slot++) {
            if (!sameItem(local.itemView(slot), remote.itemView(slot))) {
                changed.put(slot, local.item(slot));
            }
        }

        // 光标包本身不携带 state id, 因此仅光标变化不能独立构成 Delta.
        Optional<ItemStack> carried = sameItem(local.carriedView(), remote.carriedView())
                ? Optional.empty()
                : Optional.of(local.carried());
        if (changed.isEmpty() && carried.isEmpty()) {
            return new SyncPlan.None(remote.revision());
        }
        if (changed.isEmpty()) {
            return new SyncPlan.Full(local.revision(), local.items(), local.carried());
        }
        return new SyncPlan.Delta(local.revision(), changed, carried);
    }

    private static boolean sameSession(ContainerSnapshot local, ContainerSnapshot remote) {
        ContainerRevision left = local.revision();
        ContainerRevision right = remote.revision();
        return left.containerId() == right.containerId() && left.generation() == right.generation();
    }

    private static boolean sameItem(ItemStack left, ItemStack right) {
        if (left.isEmpty() && right.isEmpty()) {
            return true;
        }
        return left.getAmount() == right.getAmount() && left.isSimilar(right);
    }
}
