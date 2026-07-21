package net.momirealms.sparrow.ui.internal.menu;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 管理 Window 当前协议版本以及最后一次成功提交的远端快照.
 *
 * <p>{@link PreparedSync#commit()} 必须在发送成功后调用. 创建计划不会提前推进远端状态.</p>
 */
@ApiStatus.Internal
public final class ContainerStateTracker {
    private ContainerRevision revision;
    private @Nullable ContainerSnapshot remote;

    /**
     * 为一个 Window 会话建立从 state id 0 开始的状态跟踪器.
     *
     * @param containerId Minecraft 容器编号
     * @param generation Window 代际
     */
    public ContainerStateTracker(int containerId, long generation) {
        this.revision = new ContainerRevision(containerId, 0, generation);
    }

    /**
     * 判断一个入站操作是否属于当前协议版本.
     *
     * @param containerId 入站包中的容器编号
     * @param stateId 入站包中的状态编号
     * @param generation 接收该包的 Window 代际
     * @return 三个标识均与当前会话一致时返回 {@code true}
     */
    public boolean accepts(int containerId, int stateId, long generation) {
        return this.revision.containerId() == containerId && this.revision.stateId() == stateId && this.revision.generation() == generation;
    }

    public int stateId() {
        return this.revision.stateId();
    }

    /**
     * 根据权威物品状态准备一次发送, 但不立即改变远端快照.
     *
     * <p>调用方只有在对应数据包成功写入后才能调用 {@link PreparedSync#commit()}.
     * 这样发送失败时, 下一次比较仍会基于最后一次成功提交的远端状态.</p>
     *
     * @param slots 按原始槽位编号排列的权威物品
     * @param cursor 权威光标物品
     * @param forceFull 是否忽略差异并发送完整状态
     * @return 可发送且可在成功后提交的计划
     */
    public @NotNull PreparedSync prepare(@NotNull List<? extends ItemStack> slots, @NotNull ItemStack cursor, boolean forceFull) {
        ContainerRevision candidateRevision = this.revision.nextState();
        ContainerSnapshot candidate = new ContainerSnapshot(slots, cursor, candidateRevision);
        SyncPlan plan = ContainerSynchronizer.reconcile(candidate, this.remote, forceFull);
        if (plan instanceof SyncPlan.None) {
            return new PreparedSync(this, plan, null);
        }
        return new PreparedSync(this, plan, candidate);
    }

    /**
     * 尚未提交的发送计划. commit 幂等, 但过期计划会被拒绝.
     */
    @ApiStatus.Internal
    public static final class PreparedSync {
        private final ContainerStateTracker owner;
        private final SyncPlan plan;
        private final @Nullable ContainerSnapshot candidate;
        private boolean committed;

        private PreparedSync(ContainerStateTracker owner, SyncPlan plan, @Nullable ContainerSnapshot candidate) {
            this.owner = owner;
            this.plan = plan;
            this.candidate = candidate;
        }

        /**
         * 返回准备好的计划, 不会推进远端快照.
         *
         * @return 待发送的同步计划
         */
        public @NotNull SyncPlan plan() {
            return this.plan;
        }

        /**
         * 在发送成功后提交候选快照与其 state id.
         *
         * <p>同一计划可重复提交, 但若另一个计划已推进状态, 过期计划会被拒绝.</p>
         *
         * @throws IllegalStateException 此计划不再属于当前容器版本
         */
        public void commit() {
            if (this.committed) {
                return;
            }
            if (this.candidate != null && !this.owner.revision.nextState().equals(this.candidate.revision())) {
                throw new IllegalStateException("sync plan no longer belongs to the current container revision");
            }
            this.committed = true;
            if (this.candidate != null) {
                this.owner.revision = this.candidate.revision();
                this.owner.remote = this.candidate;
            }
        }
    }
}
