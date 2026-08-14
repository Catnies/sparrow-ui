package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

/**
 * SlotKey 用来判断两个 Inventory 槽位最终是否指向同一个存储位置.
 * <p>{@code owner} 是这一格的最终归属, 自己持有数据的 Inventory 用它自己, 引用外部存储的 Inventory
 * 用 {@link ExternalStorage#keyOf(int)} 给出的那个.
 *
 * @param owner 这一格的最终归属
 * @param slot 该归属内部的槽位编号
 */
public record SlotKey(@NotNull Object owner, int slot) {
}
