package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 将 Click Container 协议输入解释为 Window 可分派的单次点击或完整拖拽.
 * <p>该解释器拥有 QUICK_CRAFT 的跨包状态, 并统一处理普通点击终止未完成拖拽、协议字段校验和Window 槽位路由.
 */
final class ClickInterpreter {
    private @Nullable ActiveDrag activeDrag;

    /**
     * 解释一个已经通过 container/state 校验的 Click Container 输入.
     * 普通点击会先终止未完成的 QUICK_CRAFT, 使跨包状态不会泄漏到另一种交互.
     *
     * @param interaction 已解码的容器交互
     * @param layout 当前 Window 布局
     * @param generation 当前 Window generation
     * @return 等待、单次点击、完整拖拽或拒绝结果
     */
    @NotNull
    Result interpret(@NotNull MenuInput.Common.Interaction interaction, @NotNull WindowLayout layout, long generation) {
        return switch (interaction) {
            case MenuInput.Common.Click click -> {
                this.reset();
                yield ClickInterpreter.interpretSingleClick(click, layout);
            }
            case MenuInput.Common.DragStep step -> this.interpretDrag(step, layout, generation);
        };
    }

    /**
     * 丢弃尚未完成的 QUICK_CRAFT 状态.
     */
    void reset() {
        this.activeDrag = null;
    }

    /**
     * 推进已经由网络适配层解码的 QUICK_CRAFT 状态机.
     */
    private Result interpretDrag(MenuInput.Common.DragStep step, WindowLayout layout, long generation) {
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

    private Result startDrag(ClickType clickType, long generation) {
        if (this.activeDrag != null) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }

        this.activeDrag = new ActiveDrag(clickType, generation);
        return Result.Pending.INSTANCE;
    }

    private Result addDrag(ClickType clickType, int windowSlot, long generation, WindowLayout layout) {
        ActiveDrag drag = this.activeDrag;
        if (drag == null || !drag.matches(clickType, generation)) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }
        if (windowSlot < 0 || windowSlot >= layout.protocolSize()) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_SLOT);
        }

        drag.slots.add(windowSlot);
        return Result.Pending.INSTANCE;
    }

    private Result completeDrag(ClickType clickType, long generation) {
        ActiveDrag drag = this.activeDrag;
        if (drag == null || !drag.matches(clickType, generation) || drag.slots.isEmpty()) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }

        Result.Drag completed = new Result.Drag(clickType, List.copyOf(drag.slots));
        this.reset();
        return completed;
    }

    private static Result interpretSingleClick(MenuInput.Common.Click packet, WindowLayout layout) {
        ClickType clickType = packet.clickType();
        if (!ClickInterpreter.isSingleClick(clickType)) {
            return new Result.Rejected(Rejection.INVALID_BUTTON);
        }
        if (clickType == ClickType.NUMBER_KEY) {
            if (packet.hotbarButton() < 0 || packet.hotbarButton() > 8) {
                return new Result.Rejected(Rejection.INVALID_BUTTON);
            }
        } else if (packet.hotbarButton() != -1) {
            return new Result.Rejected(Rejection.INVALID_BUTTON);
        }

        Target target = ClickInterpreter.target(packet.slot(), layout);
        if (target == null) {
            return new Result.Rejected(Rejection.INVALID_SLOT);
        }
        return new Result.SingleClick(clickType, packet.hotbarButton(), target);
    }

    private static boolean isSingleClick(ClickType clickType) {
        return switch (clickType) {
            case LEFT,
                 SHIFT_LEFT,
                 RIGHT,
                 SHIFT_RIGHT,
                 WINDOW_BORDER_LEFT,
                 WINDOW_BORDER_RIGHT,
                 MIDDLE,
                 NUMBER_KEY,
                 DOUBLE_CLICK,
                 DROP,
                 CONTROL_DROP,
                 SWAP_OFFHAND -> true;
            default -> false;
        };
    }

    private static boolean isDragClick(ClickType clickType) {
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT || clickType == ClickType.MIDDLE;
    }

    private static @Nullable Target target(int windowSlot, WindowLayout layout) {
        if (windowSlot == -999) return Target.OutsideTarget.INSTANCE;
        if (windowSlot < 0 || windowSlot >= layout.protocolSize()) return null;
        return switch (layout.route(windowSlot)) {
            case WindowLayout.Route.GuiRoute ignoredRoute -> new Target.GuiTarget(windowSlot);
            case WindowLayout.Route.PlayerRoute route -> new Target.PlayerTarget(windowSlot, route.inventorySlot());
        };
    }

    /**
     * 当前尚未结束的 QUICK_CRAFT 会话.
     */
    private static final class ActiveDrag {
        private final ClickType clickType;
        private final long generation;
        private final LinkedHashSet<Integer> slots = new LinkedHashSet<>();

        private ActiveDrag(ClickType clickType, long generation) {
            this.clickType = clickType;
            this.generation = generation;
        }

        private boolean matches(ClickType clickType, long generation) {
            return this.clickType == clickType && this.generation == generation;
        }
    }

    /**
     * 无法安全解释协议输入的原因.
     */
    enum Rejection {
        INVALID_BUTTON,
        INVALID_SLOT,
        INVALID_DRAG_SEQUENCE
    }

    /**
     * 已解释单次点击的目标区域.
     */
    sealed interface Target permits Target.GuiTarget, Target.PlayerTarget, Target.OutsideTarget {

        /**
         * GUI 管理的窗口槽位.
         */
        record GuiTarget(int windowSlot) implements Target {
        }

        /**
         * 玩家原生物品栏管理的窗口槽位.
         */
        record PlayerTarget(int windowSlot, int inventorySlot) implements Target {
        }

        /**
         * 容器外点击区域.
         */
        enum OutsideTarget implements Target {
            INSTANCE
        }
    }

    /**
     * 一个协议输入的解释结果.
     */
    sealed interface Result permits Result.Pending, Result.SingleClick, Result.Drag, Result.Rejected {

        /**
         * QUICK_CRAFT 尚未结束, 需要等待后续协议输入.
         */
        enum Pending implements Result {
            INSTANCE
        }

        /**
         * 可立即分派的单次点击.
         */
        record SingleClick(
                @NotNull ClickType clickType,
                int hotbarButton,
                @NotNull Target target
        ) implements Result {
        }

        /**
         * 已完成的 QUICK_CRAFT, 槽位按客户端首次加入顺序排列并去重.
         */
        record Drag(@NotNull ClickType clickType, @NotNull List<Integer> slots) implements Result {
        }

        /**
         * 因不支持或不可信字段拒绝的协议输入.
         */
        record Rejected(@NotNull Rejection reason) implements Result {
        }
    }
}
