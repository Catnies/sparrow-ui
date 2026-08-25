package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.window.handle.MenuInput;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;

final class ClickInterpreter {
    @Nullable private ActiveDrag activeDrag; // 正在进行中的拖拽行为, 没有时为 null, 仅实体线程访问

    // 普通点击会清空未完成拖拽, 两种协议交互不共用状态.
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

    void reset() {
        this.activeDrag = null;
    }

    private static Result interpretSingleClick(MenuInput.Common.Click packet, WindowLayout layout) {
        ClickType clickType = packet.clickType();
        if (clickType == ClickType.CREATIVE) {
            return new Result.Rejected(Rejection.INVALID_BUTTON);
        }
        if (clickType == ClickType.NUMBER_KEY && (packet.hotbarButton() < 0 || packet.hotbarButton() > 8)) {
            return new Result.Rejected(Rejection.INVALID_BUTTON);
        }

        int rawSlot = packet.slot();
        if (rawSlot != InventoryView.OUTSIDE && (rawSlot < 0 || rawSlot >= layout.protocolSize())) {
            return new Result.Rejected(Rejection.INVALID_SLOT);
        }
        return new Result.SingleClick(clickType, packet.hotbarButton(), rawSlot);
    }

    // START, ADD 和 END 必须使用同一按键并属于同一 Window generation.
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

    private static boolean isDragClick(ClickType clickType) {
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT || clickType == ClickType.MIDDLE;
    }

    private Result startDrag(ClickType clickType, long generation) {
        // 如果上一次拖拽还没结束, 代表序列不可信
        if (this.activeDrag != null) {
            this.reset();
            return new Result.Rejected(Rejection.INVALID_DRAG_SEQUENCE);
        }

        this.activeDrag = new ActiveDrag(clickType, generation);
        return Result.Pending.INSTANCE;
    }

    private Result addDrag(ClickType clickType, int windowSlot, long generation, WindowLayout layout) {
        ActiveDrag drag = this.activeDrag;
        // 按键或 generation 对不上, 说明这一步不属于当前拖拽
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

    private static final class ActiveDrag {
        private final ClickType clickType;
        private final long generation; // 拖拽开始时的 Window generation
        private final LinkedHashSet<Integer> slots = new LinkedHashSet<>(); // 拖过的槽位, 按加入顺序去重

        private ActiveDrag(ClickType clickType, long generation) {
            this.clickType = clickType;
            this.generation = generation;
        }

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

    sealed interface Result permits Result.Pending, Result.SingleClick, Result.Drag, Result.Rejected {

        enum Pending implements Result {
            INSTANCE
        }

        /**
         * 可以直接分派的单次点击. {@code rawSlot} 是协议槽位(raw slot),
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
