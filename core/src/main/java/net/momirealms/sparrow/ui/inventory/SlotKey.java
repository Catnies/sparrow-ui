package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

/**
 * SlotKey 用来判断两个当前 Inventory 槽位最终是否指向同一个存储位置.
 */
sealed interface SlotKey permits SlotKey.Anchor, SlotKey.ExternalSlot {

    /**
     * RootInventory 槽地址, 由 RootInventory 实例和该 RootInventory 内的槽位编号组成.
     * 普通 RootInventory 的槽位直接以这个地址作为 SlotKey.
     */
    record Anchor(@NotNull RootInventory root, int rootSlot) implements SlotKey {
    }

    /**
     * 外部容器槽身份. {@code owner} 表示外部容器身份, {@code slot} 表示该容器中的槽位.
     */
    record ExternalSlot(@NotNull Object owner, int slot) implements SlotKey {
    }

}
