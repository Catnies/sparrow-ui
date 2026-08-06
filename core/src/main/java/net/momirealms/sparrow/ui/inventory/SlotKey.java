package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

/**
 * SlotKey 用来判断两个 Inventory 槽位最终是否指向同一个存储位置.
 * <p>{@code owner} 是这份存储的最终归属: 自己持有数据的 Inventory 用它自己, 引用外部容器的 Inventory
 * 用那个 Bukkit 容器. 两个不同的 ReferencingInventory 指向同一个 Bukkit 容器的同一槽位时, SlotKey 相同.
 *
 * @param owner 存储的最终归属
 * @param slot 该归属内部的槽位编号
 */
record SlotKey(@NotNull Object owner, int slot) {
}
