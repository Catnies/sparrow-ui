package net.momirealms.sparrow.ui.inventory.storage;

import org.jetbrains.annotations.NotNull;

/**
 * 标识槽位最终写入的物理位置, 相同键表示同一格.
 *
 * @param owner 最终存储位置的稳定归属
 * @param slot 归属内部仅用于判等的槽号, 不可用于 Inventory 读写
 */
public record SlotKey(@NotNull Object owner, int slot) {
}
