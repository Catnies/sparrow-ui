package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把客户端发过来的 Container 协议包解释成 Window 能直接分派的单次点击或完整拖拽.
 * <p>解释器自己保管 QUICK_CRAFT(按住键拖过多个槽位)的跨包状态, 只在玩家的实体线程使用:
 * 普通点击会先终止未完成的拖拽, 协议字段和 Window 槽位范围也在这里统一校验.
 */
final class ClickInterpreter {
    @Nullable private ActiveDrag activeDrag; // 正在进行中的拖拽行为, 没有时为 null, 仅实体线程访问

    /**
     * 解释一个已经通过 container/state 校验的 Click Container 输入.
     * 普通点击会先终止未完成的拖拽, 避免跨包状态泄漏到另一种交互里.
     *
     * @param interaction 已解码的容器交互
     * @param layout 当前 Window 布局
     * @param generation 当前 Window generation
     * @return 等待, 单次点击, 完整拖拽或拒绝结果
     */
    @NotNull
    Result interpret(@NotNull MenuInput.Common.Interaction interaction, @NotNull WindowLayout layout, long generation) {
        return switch (interaction) {
            case MenuInput.Common.Click click -> {
                // 普通点击会终止还没完成的拖拽, 两种交互的状态不混用
                this.reset();
                yield ClickInterpreter.interpretSingleClick(click, layout);
            }
            case MenuInput.Common.DragStep step -> this.interpretDrag(step, layout, generation);
        };
    }

    /**
     * 丢掉还没完成的拖拽状态.
     */
    void reset() {
        this.activeDrag = null;
    }

    /**
     * 校验并解释一次普通点击.
     *
     * @param packet 已解码的点击包
     * @param layout 当前 Window 布局
     * @return 单次点击结果; 按键, 热键栏编号或槽位不合法时返回拒绝
     */
    private static Result interpretSingleClick(MenuInput.Common.Click packet, WindowLayout layout) {
        ClickType clickType = packet.clickType();
        // 只认能单独成立的点击类型
        if (clickType == ClickType.CREATIVE) {
            return new Result.Rejected(Rejection.INVALID_BUTTON);
        }
        if (clickType == ClickType.NUMBER_KEY && (packet.hotbarButton() < 0 || packet.hotbarButton() > 8)) {
            return new Result.Rejected(Rejection.INVALID_BUTTON);
        }

        // 槽位要么是容器外, 要么在协议范围内
        int rawSlot = packet.slot();
        if (rawSlot != InventoryView.OUTSIDE && (rawSlot < 0 || rawSlot >= layout.protocolSize())) {
            return new Result.Rejected(Rejection.INVALID_SLOT);
        }
        return new Result.SingleClick(clickType, packet.hotbarButton(), rawSlot);
    }

    /**
     * 推进网络层已经解码好的拖拽状态机.
     *
     * @param step 拖拽的一步(开始, 添加槽位或结束)
     * @param layout 当前 Window 布局
     * @param generation 当前 Window generation
     * @return 等待, 完整拖拽或拒绝结果
     */
    private Result interpretDrag(MenuInput.Common.DragStep step, WindowLayout layout, long generation) {
        // 拖拽只认左键, 右键和中键, 其他按键直接拒绝
        if (!ClickInterpreter.isDragClick(step.clickType())) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_BUTTON);
        }

        return switch (step.phase()) {
            case START -> this.startDrag(step.clickType(), generation);
            case ADD -> this.addDrag(step.clickType(), step.slot(), generation, layout);
            case END -> this.completeDrag(step.clickType(), generation);
        };
    }

    /**
     * 判断点击类型能否用于拖拽.
     *
     * @param clickType 点击类型
     * @return 能用于拖拽时返回 true
     */
    private static boolean isDragClick(ClickType clickType) {
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT || clickType == ClickType.MIDDLE;
    }

    /**
     * 开始一次新拖拽.
     *
     * @param clickType 拖拽按键
     * @param generation 当前 Window generation
     * @return 正常开始时返回等待结果; 上一次拖拽还没结束又来了 START 时返回拒绝
     */
    private Result startDrag(ClickType clickType, long generation) {
        // 如果上一次拖拽还没结束, 代表序列不可信
        if (this.activeDrag != null) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }

        this.activeDrag = new ActiveDrag(clickType, generation);
        return Result.Pending.INSTANCE;
    }

    /**
     * 把一格加进进行中的拖拽.
     *
     * @param clickType 拖拽按键
     * @param windowSlot 客户端声明的协议槽位(raw slot)
     * @param generation 当前 Window generation
     * @param layout 当前 Window 布局
     * @return 正常添加时返回等待结果; 拖拽对不上或槽位越界时返回拒绝
     */
    private Result addDrag(ClickType clickType, int windowSlot, long generation, WindowLayout layout) {
        ActiveDrag drag = this.activeDrag;
        // 按键或 generation 对不上, 说明这一步不属于当前拖拽
        if (drag == null || !drag.matches(clickType, generation)) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }
        // 槽位必须在协议范围内
        if (windowSlot < 0 || windowSlot >= layout.protocolSize()) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_SLOT);
        }

        drag.slots.add(windowSlot);
        return Result.Pending.INSTANCE;
    }

    /**
     * 结束进行中的拖拽并产出完整结果.
     *
     * @param clickType 拖拽按键
     * @param generation 当前 Window generation
     * @return 单槽左右键按原版回退为普通点击; 其他情况返回完整拖拽, 序列不合法时返回拒绝
     */
    private Result completeDrag(ClickType clickType, long generation) {
        ActiveDrag drag = this.activeDrag;
        // 拖拽对不上, 或一格都没拖过就 END, 序列不可信
        if (drag == null || !drag.matches(clickType, generation) || drag.slots.isEmpty()) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }

        // 原版单槽左/右 QUICK_CRAFT 不发布拖拽, 而是用拖拽按键重新执行一次普通 PICKUP.
        Result completed = drag.slots.size() == 1 && clickType != ClickType.MIDDLE
                ? new Result.SingleClick(clickType, -1, drag.slots.getFirst())
                : new Result.Drag(clickType, List.copyOf(drag.slots));
        this.reset();
        return completed;
    }

    /**
     * 一次还没结束的拖拽.
     */
    private static final class ActiveDrag {
        private final ClickType clickType; // 发起拖拽的按键
        private final long generation; // 拖拽开始时的 Window generation
        private final LinkedHashSet<Integer> slots = new LinkedHashSet<>(); // 拖过的槽位, 按加入顺序去重

        /**
         * 记录一次新拖拽的起点.
         *
         * @param clickType 发起拖拽的按键
         * @param generation 拖拽开始时的 Window generation
         */
        private ActiveDrag(ClickType clickType, long generation) {
            this.clickType = clickType;
            this.generation = generation;
        }

        /**
         * 判断后续步骤是否还属于这次拖拽.
         *
         * @param clickType 后续步骤的按键
         * @param generation 后续步骤到达时的 Window generation
         * @return 按键和 generation 都与起点一致时返回 true
         */
        private boolean matches(ClickType clickType, long generation) {
            return this.clickType == clickType && this.generation == generation;
        }
    }

    /**
     * 协议输入无法安全解释的原因.
     */
    enum Rejection {
        INVALID_BUTTON,        // 按键不合法或不支持
        INVALID_SLOT,          // 槽位超出协议范围
        INVALID_DRAG_SEQUENCE  // 拖拽步骤的顺序或归属对不上
    }

    /**
     * 一个协议输入的解释结果.
     */
    sealed interface Result permits Result.Pending, Result.SingleClick, Result.Drag, Result.Rejected {

        /**
         * 拖拽还没结束, 等后续协议输入.
         */
        enum Pending implements Result {
            INSTANCE
        }

        /**
         * 可以直接分派的单次点击; {@code rawSlot} 是协议槽位(raw slot),
         * 也可能是 {@link InventoryView#OUTSIDE}.
         *
         * @param clickType 点击类型
         * @param hotbarButton 热键栏编号, 仅 NUMBER_KEY 有效, 其他情况为 -1
         * @param rawSlot 协议槽位(raw slot)或 {@link InventoryView#OUTSIDE}
         */
        record SingleClick(
                @NotNull ClickType clickType,
                int hotbarButton,
                int rawSlot
        ) implements Result {
        }

        /**
         * 已完成的拖拽, 槽位按客户端首次加入的顺序排列并去重.
         *
         * @param clickType 拖拽按键
         * @param slots 拖过的协议槽位(raw slot)
         */
        record Drag(@NotNull ClickType clickType, @NotNull List<Integer> slots) implements Result {
        }

        /**
         * 因字段不支持或不可信而拒绝的协议输入.
         *
         * @param reason 拒绝原因
         */
        record Rejected(@NotNull Rejection reason) implements Result {
        }
    }
}
