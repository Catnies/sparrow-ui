package net.momirealms.sparrow.ui.window.handle;

import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 从 NMS 包翻过来的入站操作消息.
 * <p>客户端预测裹在不透明的 {@link MenuPrediction} 里跟着交互一起传, Window 按操作意图更新自己的渲染结果.
 */
@ApiStatus.Internal
public sealed interface MenuInput permits MenuInput.Common, MenuInput.WindowSpecific {

    sealed interface Common extends MenuInput permits Common.Interaction, Common.Close, Common.BundleSelection, Common.Pong {

        // 拖拽手势的三个阶段: 起手, 途中, 收尾
        enum DragPhase {
            START,
            ADD,
            END
        }

        // 一次交互共有的部分: 属于哪个容器, 客户端认为现在是什么 state, 点在哪一格, 以及客户端预测
        sealed interface Interaction extends Common permits Click, DragStep {

            int containerId();

            int stateId();

            int slot();

            @NotNull MenuPrediction prediction();
        }

        // 一次单击, 点击类型和数字键槽位都在里面
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

        // 拖拽里的一步, 带着阶段
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

        // 客户端在收纳袋界面里换了选中项
        record BundleSelection(int containerId, int slot, int selectedIndex) implements Common {
        }

        // 客户端请求关掉容器
        record Close(int containerId) implements Common {
        }

        // 客户端对 ping 的回应
        record Pong(int id) implements Common {
        }
    }

    non-sealed interface WindowSpecific extends MenuInput {

        /**
         * 客户端在铁砧文本框里提交的新名字.
         *
         * @param text 新名字
         */
        record Rename(@NotNull String text) implements WindowSpecific {
        }

        /**
         * 客户端把合成器的某个输入槽启用了或者禁用了.
         *
         * @param containerId 目标容器编号
         * @param slot 输入槽编号
         * @param enabled true 表示客户端请求启用该槽位
         */
        record CrafterSlotState(int containerId, int slot, boolean enabled) implements WindowSpecific {
        }

        /**
         * 客户端点了菜单里的一个原版按钮.
         *
         * @param containerId 目标容器编号
         * @param button 按钮编号
         */
        record ButtonClick(int containerId, int button) implements WindowSpecific {
        }

        /**
         * 客户端在配方书里选了一个 recipe display.
         *
         * @param containerId 目标容器编号
         * @param displayId recipe display 编号
         * @param makeAll true 表示客户端请求尽可能多地制作
         */
        record RecipePlace(int containerId, int displayId, boolean makeAll) implements WindowSpecific {
        }

        /**
         * 客户端在商人界面里选了一项交易.
         *
         * @param containerId 接收包时所属的容器编号
         * @param index 交易索引
         */
        record TradeSelect(int containerId, int index) implements WindowSpecific {
        }
    }
}
