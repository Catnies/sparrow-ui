package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.ApiStatus;

/**
 * 标识一次 Window 菜单会话及客户端当前应持有的 state id.
 *
 * @param containerId Minecraft 容器编号
 * @param stateId 协议状态编号
 * @param generation Window 代际
 */
@ApiStatus.Internal
public record ContainerRevision(int containerId, int stateId, long generation) {

    /**
     * 验证容器会话标识与协议状态编号的有效范围.
     *
     * @throws IllegalArgumentException 容器编号、状态编号或代际不在协议允许范围内
     */
    public ContainerRevision {
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
        if (stateId < 0 || stateId > 32767) {
            throw new IllegalArgumentException("stateId must be between 0 and 32767");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
    }

    /**
     * 按 Minecraft 容器协议的 15 位范围推进 state id.
     *
     * @return 同一会话的下一版本
     */
    public ContainerRevision nextState() {
        return new ContainerRevision(this.containerId, (this.stateId + 1) & 32767, this.generation);
    }
}
