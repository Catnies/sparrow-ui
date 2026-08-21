package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.ClickSemantics;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface PlayerUpdateReason extends UpdateReason {

    /**
     * 返回发起交互的玩家.
     *
     * @return 发起交互的玩家
     */
    @NotNull
    Player player();

    /**
     * 玩家点击触发的 Inventory 事务.
     *
     * @param player 发起点击的玩家
     * @param clickType 点击类型
     * @param hotbarButton 数字键编号, 0 到 8; 非数字键点击为 -1
     */
    record Click(
            @NotNull Player player,
            @NotNull ClickType clickType,
            int hotbarButton
    ) implements PlayerUpdateReason {
    }

    /**
     * 玩家拖拽分配触发的 Inventory 事务.
     *
     * @param player 发起拖拽的玩家
     * @param clickType 拖拽按键(LEFT 均分, RIGHT 每槽一个, MIDDLE 创造整堆)
     * @param slots 手势经过并成功解析出的 InventoryLink 槽位, 按手势顺序排列且不含重复 SlotKey
     */
    record Drag(
            @NotNull Player player,
            @NotNull ClickType clickType,
            @NotNull List<ClickSemantics.LinkedSlot> slots
    ) implements PlayerUpdateReason {

        public Drag {
            slots = List.copyOf(slots);
        }
    }

    /**
     * 玩家选择 Bundle 内部物品后触发的 Inventory 事务.
     *
     * @param player 发起选择的玩家
     * @param bundleSlot Bundle 内槽位; {@code -1} 表示光标已离开
     */
    record BundleSelect(
            @NotNull Player player,
            int bundleSlot
    ) implements PlayerUpdateReason {
    }
}
