package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 将 Click Container 协议输入解释为 Window 可分派的单次点击或完整拖拽.
 *
 * <p>该解释器拥有 QUICK_CRAFT 的跨包状态, 并统一处理普通点击终止未完成拖拽、协议字段校验和
 * Window 槽位路由. 它不触发 Bukkit 事件, 也不读取或修改容器物品状态.</p>
 */
final class ClickInterpreter {

    /**
     * 无法安全解释协议输入的原因.
     */
    enum Rejection {
        UNSUPPORTED_ACTION,
        INVALID_BUTTON,
        INVALID_SLOT,
        INVALID_DRAG_SEQUENCE
    }




    /**
     * 已解释单次点击的目标区域.
     */
    sealed interface Target permits GuiTarget, PlayerTarget, OutsideTarget {
    }

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




    /**
     * 一个协议输入的解释结果.
     */
    sealed interface Result permits Pending, SingleClick, Drag, Rejected {
    }

    /**
     * QUICK_CRAFT 尚未结束, 需要等待后续协议输入.
     */
    enum Pending implements Result {
        INSTANCE
    }

    /**
     * 可立即分派的单次点击.
     */
    record SingleClick(@NotNull ClickType clickType, int hotbarButton, @NotNull Target target) implements Result {
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




    private @Nullable ActiveDrag activeDrag;

    /**
     * 解释一个已经通过 container/state 校验的 Click Container 输入.
     * 普通点击会先终止未完成的 QUICK_CRAFT, 使跨包状态不会泄漏到另一种交互.
     *
     * @param packet 原始点击输入
     * @param layout 当前 Window 布局
     * @param generation 当前 Window generation
     * @return 等待、单次点击、完整拖拽或拒绝结果
     */
    @NotNull
    Result interpret(@NotNull MenuInput.Click packet, @NotNull WindowLayout layout, long generation) {
        if (packet.action() == MenuInput.Action.QUICK_CRAFT) {
            return this.interpretDrag(packet, layout, generation);
        }

        this.reset();
        return ClickInterpreter.interpretSingleClick(packet, layout);
    }

    /**
     * 丢弃尚未完成的 QUICK_CRAFT 状态.
     */
    void reset() {
        this.activeDrag = null;
    }

    /**
     * 推进 QUICK_CRAFT 状态机.
     * button 编码不是连续的 phase 值, 因此先显式解码为带模式的协议步骤, 再执行状态转移.
     */
    private Result interpretDrag(MenuInput.Click packet, WindowLayout layout, long generation) {
        QuickCraftStep step = QuickCraftStep.fromButton(packet.button());
        if (step == null) {
            this.reset();
            return new Rejected(Rejection.INVALID_BUTTON);
        }

        return switch (step.phase()) {
            case START -> this.startDrag(step.clickType(), generation);
            case ADD -> this.addDrag(step.clickType(), packet.slot(), generation, layout);
            case END -> this.completeDrag(step.clickType(), generation);
        };
    }

    private Result startDrag(ClickType clickType, long generation) {
        if (this.activeDrag != null) {
            this.reset();
            return new Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }

        this.activeDrag = new ActiveDrag(clickType, generation);
        return Pending.INSTANCE;
    }

    private Result addDrag(ClickType clickType, int windowSlot, long generation, WindowLayout layout) {
        ActiveDrag drag = this.activeDrag;
        if (drag == null || !drag.matches(clickType, generation)) {
            this.reset();
            return new Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }
        if (windowSlot < 0 || windowSlot >= layout.size()) {
            this.reset();
            return new Rejected(Rejection.INVALID_SLOT);
        }

        drag.slots.add(windowSlot);
        return Pending.INSTANCE;
    }

    private Result completeDrag(ClickType clickType, long generation) {
        ActiveDrag drag = this.activeDrag;
        if (drag == null || !drag.matches(clickType, generation) || drag.slots.isEmpty()) {
            this.reset();
            return new Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }

        Drag completed = new Drag(clickType, List.copyOf(drag.slots));
        this.reset();
        return completed;
    }

    private static Result interpretSingleClick(MenuInput.Click packet, WindowLayout layout) {
        ClickType clickType;
        int hotbarButton = -1;
        switch (packet.action()) {
            case PICKUP -> {
                if (packet.button() == 0) {
                    clickType = packet.slot() == -999 ? ClickType.WINDOW_BORDER_LEFT : ClickType.LEFT;
                } else if (packet.button() == 1) {
                    clickType = packet.slot() == -999 ? ClickType.WINDOW_BORDER_RIGHT : ClickType.RIGHT;
                } else {
                    return new Rejected(Rejection.INVALID_BUTTON);
                }
            }
            case QUICK_MOVE -> {
                if (packet.button() == 0) {
                    clickType = ClickType.SHIFT_LEFT;
                } else if (packet.button() == 1) {
                    clickType = ClickType.SHIFT_RIGHT;
                } else {
                    return new Rejected(Rejection.INVALID_BUTTON);
                }
            }
            case SWAP -> {
                if (packet.button() >= 0 && packet.button() <= 8) {
                    clickType = ClickType.NUMBER_KEY;
                    hotbarButton = packet.button();
                } else if (packet.button() == 40) {
                    clickType = ClickType.SWAP_OFFHAND;
                } else {
                    return new Rejected(Rejection.INVALID_BUTTON);
                }
            }
            case CLONE -> {
                if (packet.button() != 2) {
                    return new Rejected(Rejection.INVALID_BUTTON);
                }
                clickType = ClickType.MIDDLE;
            }
            case THROW -> {
                if (packet.button() == 0) {
                    clickType = ClickType.DROP;
                } else if (packet.button() == 1) {
                    clickType = ClickType.CONTROL_DROP;
                } else {
                    return new Rejected(Rejection.INVALID_BUTTON);
                }
            }
            case PICKUP_ALL -> {
                if (packet.button() != 0) {
                    return new Rejected(Rejection.INVALID_BUTTON);
                }
                clickType = ClickType.DOUBLE_CLICK;
            }
            case QUICK_CRAFT -> throw new AssertionError("QUICK_CRAFT must be handled by the drag interpreter");
            default -> {
                return new Rejected(Rejection.UNSUPPORTED_ACTION);
            }
        }

        Target target = ClickInterpreter.target(packet.slot(), layout);
        if (target == null) {
            return new Rejected(Rejection.INVALID_SLOT);
        }
        if (target == OutsideTarget.INSTANCE && packet.action() != MenuInput.Action.PICKUP) {
            return new Rejected(Rejection.INVALID_SLOT);
        }
        return new SingleClick(clickType, hotbarButton, target);
    }

    private static @Nullable Target target(int windowSlot, WindowLayout layout) {
        if (windowSlot == -999) {
            return OutsideTarget.INSTANCE;
        }
        if (windowSlot < 0 || windowSlot >= layout.size()) {
            return null;
        }
        return switch (layout.route(windowSlot)) {
            case WindowLayout.GuiRoute _ -> new GuiTarget(windowSlot);
            case WindowLayout.PlayerRoute route -> new PlayerTarget(windowSlot, route.inventorySlot());
        };
    }

    private enum DragPhase {
        START,
        ADD,
        END
    }

    /**
     * QUICK_CRAFT button 的九种合法组合.
     * 显式列出协议值, 避免调用方理解 button 低两位 phase 与高位模式的位编码.
     */
    private enum QuickCraftStep {
        LEFT_START(DragPhase.START, ClickType.LEFT),
        LEFT_ADD(DragPhase.ADD, ClickType.LEFT),
        LEFT_END(DragPhase.END, ClickType.LEFT),
        RIGHT_START(DragPhase.START, ClickType.RIGHT),
        RIGHT_ADD(DragPhase.ADD, ClickType.RIGHT),
        RIGHT_END(DragPhase.END, ClickType.RIGHT),
        MIDDLE_START(DragPhase.START, ClickType.MIDDLE),
        MIDDLE_ADD(DragPhase.ADD, ClickType.MIDDLE),
        MIDDLE_END(DragPhase.END, ClickType.MIDDLE);

        private final DragPhase phase;
        private final ClickType clickType;

        QuickCraftStep(DragPhase phase, ClickType clickType) {
            this.phase = phase;
            this.clickType = clickType;
        }

        private DragPhase phase() {
            return this.phase;
        }

        private ClickType clickType() {
            return this.clickType;
        }

        private static @Nullable QuickCraftStep fromButton(int button) {
            return switch (button) {
                case 0 -> LEFT_START;
                case 1 -> LEFT_ADD;
                case 2 -> LEFT_END;
                case 4 -> RIGHT_START;
                case 5 -> RIGHT_ADD;
                case 6 -> RIGHT_END;
                case 8 -> MIDDLE_START;
                case 9 -> MIDDLE_ADD;
                case 10 -> MIDDLE_END;
                default -> null;
            };
        }
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
}
