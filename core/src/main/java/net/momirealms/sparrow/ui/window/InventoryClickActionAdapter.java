package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.inventory.event.InventoryClickAction;
import org.bukkit.event.inventory.InventoryAction;
import org.jetbrains.annotations.NotNull;

final class InventoryClickActionAdapter {
    private static final InventoryAction[] BUKKIT_ACTIONS = bukkitActions();

    private InventoryClickActionAdapter() {
    }

    @NotNull
    static InventoryAction toBukkit(@NotNull InventoryClickAction action) {
        return BUKKIT_ACTIONS[action.ordinal()];
    }

    // 按当前服务端的枚举建立映射, 平台无法表达的动作使用 UNKNOWN.
    private static InventoryAction[] bukkitActions() {
        InventoryClickAction[] actions = InventoryClickAction.values();
        InventoryAction[] mapped = new InventoryAction[actions.length];
        for (int index = 0; index < actions.length; index++) {
            try {
                mapped[index] = InventoryAction.valueOf(actions[index].name());
            } catch (IllegalArgumentException exception) {
                mapped[index] = InventoryAction.UNKNOWN;
            }
        }
        return mapped;
    }
}
