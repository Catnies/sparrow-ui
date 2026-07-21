package net.momirealms.sparrow.ui.internal.menu;

import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 从 NMS 包转换出的稳定入站消息.
 *
 * <p>客户端预测以不透明的 {@link MenuPrediction} 随交互传递, 但不作为权威状态. Window
 * 只根据操作意图更新自身的权威渲染结果, Paper 菜单 Adapter 只用预测缩小远端复核范围.</p>
 */
@ApiStatus.Internal
public sealed interface MenuInput permits MenuInput.Interaction, MenuInput.Close, MenuInput.BundleSelection, MenuInput.Pong {

    /**
     * QUICK_CRAFT 手势中的一个阶段.
     */
    enum DragPhase {
        START,
        ADD,
        END
    }

    /**
     * 携带容器状态编号的玩家交互.
     */
    sealed interface Interaction extends MenuInput permits Click, DragStep {

        /**
         * 返回目标容器编号.
         *
         * @return 目标容器编号
         */
        int containerId();

        /**
         * 返回客户端声称的容器状态编号.
         *
         * @return 容器状态编号
         */
        int stateId();

        /**
         * 返回客户端声称已经改变的远端容器预测.
         *
         * @return 非权威客户端预测
         */
        @NotNull MenuPrediction prediction();
    }

    /**
     * 客户端对一个原始槽位的单次点击意图.
     *
     * @param containerId 目标容器编号
     * @param stateId 客户端声称的容器状态编号
     * @param slot 原始槽位编号
     * @param clickType Bukkit 点击类型
     * @param hotbarButton {@link ClickType#NUMBER_KEY} 对应的快捷栏索引, 其他点击为 {@code -1}
     * @param prediction 客户端声称的非权威远端状态
     */
    record Click(
            int containerId,
            int stateId,
            int slot,
            @NotNull ClickType clickType,
            int hotbarButton,
            @NotNull MenuPrediction prediction
    ) implements Interaction {

        public Click(int containerId, int stateId, int slot, @NotNull ClickType clickType, int hotbarButton) {
            this(containerId, stateId, slot, clickType, hotbarButton, MenuPrediction.empty());
        }
    }

    /**
     * 客户端 QUICK_CRAFT 手势中的一个输入步骤.
     *
     * @param containerId 目标容器编号
     * @param stateId 客户端声称的容器状态编号
     * @param slot 当前步骤携带的原始槽位编号
     * @param clickType 拖拽使用的 Bukkit 点击类型
     * @param phase 手势阶段
     * @param prediction 客户端声称的非权威远端状态
     */
    record DragStep(
            int containerId,
            int stateId,
            int slot,
            @NotNull ClickType clickType,
            @NotNull DragPhase phase,
            @NotNull MenuPrediction prediction
    ) implements Interaction {

        public DragStep(
                int containerId,
                int stateId,
                int slot,
                @NotNull ClickType clickType,
                @NotNull DragPhase phase
        ) {
            this(containerId, stateId, slot, clickType, phase, MenuPrediction.empty());
        }
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
