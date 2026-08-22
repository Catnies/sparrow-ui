package net.momirealms.sparrow.ui.inventory.storage;

import java.util.UUID;

// 世界里的一个方块位置, 拿来当 SlotKey 的归属.
record BlockAnchor(UUID world, long pos) {
}
