package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 从 NMS 包转换出的稳定入站消息.
 *
 * <p>客户端预测的槽位和光标不进入领域消息, 也绝不作为权威状态. Window 只根据这些操作意图
 * 更新自身的权威渲染结果.</p>
 */
@ApiStatus.Internal
public sealed interface MenuInput permits MenuInput.Click, MenuInput.Close, MenuInput.BundleSelection, MenuInput.Pong {

    /**
     * Minecraft 容器点击动作的稳定分类.
     */
    enum Action {
        PICKUP,
        QUICK_MOVE,
        SWAP,
        CLONE,
        THROW,
        QUICK_CRAFT,
        PICKUP_ALL
    }

    /**
     * 客户端对一个原始槽位的点击意图.
     *
     * @param containerId 目标容器编号
     * @param stateId 客户端声称的容器状态编号
     * @param slot 原始槽位编号
     * @param button 鼠标按钮或热键编号
     * @param action 协议点击动作
     */
    record Click(
            int containerId,
            int stateId,
            int slot,
            int button,
            @NotNull Action action
    ) implements MenuInput {
    }

    /**
     * 客户端请求关闭容器.
     *
     * @param containerId 目标容器编号
     */
    record Close(int containerId) implements MenuInput {
    }

    /**
     * 客户端在 bundle 物品中选择了一个内部条目.
     *
     * @param containerId 接收包时所属的容器编号
     * @param slot bundle 所在的原始槽位
     * @param selectedIndex 被选中的 bundle 内部索引
     */
    record BundleSelection(int containerId, int slot, int selectedIndex) implements MenuInput {
    }

    /**
     * 客户端对 Window 状态 Ping 的确认.
     *
     * @param id Ping 标识
     */
    record Pong(int id) implements MenuInput {
    }
}
