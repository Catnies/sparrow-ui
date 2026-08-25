package net.momirealms.sparrow.ui.window.handle;

import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 从 NMS 包转换出的入站操作消息.
 * <p>客户端预测以不透明的 {@link MenuPrediction} 随交互传递, Window根据操作意图更新自身的渲染结果.</p>
 */
@ApiStatus.Internal
public sealed interface MenuInput permits MenuInput.Common, MenuInput.WindowSpecific {

    sealed interface Common extends MenuInput permits Common.Interaction, Common.Close, Common.BundleSelection, Common.Pong {

        enum DragPhase {
            START,
            ADD,
            END
        }

        sealed interface Interaction extends Common permits Click, DragStep {

            int containerId();

            int stateId();

            int slot();

            @NotNull MenuPrediction prediction();
        }

        record Click(int containerId, int stateId, int slot, @NotNull ClickType clickType, int hotbarButton, @NotNull MenuPrediction prediction) implements Interaction {

            public Click(
                    int containerId,
                    int stateId,
                    int slot,
                    @NotNull ClickType clickType,
                    int hotbarButton
            ) {
                this(containerId, stateId, slot, clickType, hotbarButton, MenuPrediction.empty());
            }
        }

        record DragStep(int containerId, int stateId, int slot, @NotNull ClickType clickType, @NotNull DragPhase phase, @NotNull MenuPrediction prediction) implements Interaction {

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

        record BundleSelection(int containerId, int slot, int selectedIndex) implements Common {
        }

        record Close(int containerId) implements Common {
        }

        record Pong(int id) implements Common {
        }
    }

    non-sealed interface WindowSpecific extends MenuInput {

        /**
         * 客户端在铁砧文本框中提交了新的重命名文本.
         *
         * @param text 新文本
         */
        record Rename(@NotNull String text) implements WindowSpecific {
        }

        /**
         * 客户端切换了合成器输入槽的启用状态.
         *
         * @param containerId 目标容器编号
         * @param slot 输入槽编号
         * @param enabled true 表示客户端请求启用该槽位
         */
        record CrafterSlotState(int containerId, int slot, boolean enabled) implements WindowSpecific {
        }

        /**
         * 客户端选择了菜单中的一个原版按钮.
         *
         * @param containerId 目标容器编号
         * @param button 按钮编号
         */
        record ButtonClick(int containerId, int button) implements WindowSpecific {
        }

        /**
         * 客户端从配方书选择了一个 recipe display.
         *
         * @param containerId 目标容器编号
         * @param displayId recipe display 编号
         * @param makeAll true 表示客户端请求尽可能多地制作
         */
        record RecipePlace(int containerId, int displayId, boolean makeAll) implements WindowSpecific {
        }

        /**
         * 客户端选择了商人界面中的一项交易.
         *
         * @param containerId 接收包时所属的容器编号
         * @param index 交易索引
         */
        record TradeSelect(int containerId, int index) implements WindowSpecific {
        }
    }
}
