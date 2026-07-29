package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

/**
 * 槽位的最终物理身份, 用来判断两个逻辑槽背后是不是同一块真实存储.
 */
sealed interface SlotKey permits SlotKey.Anchor, SlotKey.ExternalSlot {

    /**
     * 逻辑槽在根Inventory里的落点(哪个根Inventory的哪个槽);
     * 普通根Inventory的槽位以此作为最终物理身份.
     */
    record Anchor(@NotNull AbstractInventory root, int rootSlot) implements SlotKey {
    }

    /**
     * 多个镜像根Inventory共同指向的外部真实槽位.
     */
    record ExternalSlot(@NotNull Object owner, int slot) implements SlotKey {
    }

}
