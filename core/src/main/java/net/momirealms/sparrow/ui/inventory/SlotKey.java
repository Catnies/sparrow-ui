package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

/**
 * 判断两个 Inventory 的槽位是不是同一个存储位置, 相等就认为是同一格.
 *
 * @param owner 这一格的最终归属, 自己存数据的 Inventory 是它自己, 引用外部存储的取 {@link ExternalStorage#keyOf(int)}
 * @param slot 该归属内部的槽位编号
 */
public record SlotKey(@NotNull Object owner, int slot) {
}
