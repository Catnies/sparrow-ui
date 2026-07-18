package net.momirealms.sparrow.ui.util;

import org.bukkit.inventory.ItemStack;

public final class ItemSnapshots {

    private ItemSnapshots() {
    }

    /**
     * 返回可独立修改的快照, 并规范化缺失或空的物品堆.
     *
     * @param source 源物品堆, 或 {@code null}
     * @return 归调用方所有的物品堆快照
     */
    public static ItemStack copyOrEmpty(ItemStack source) {
        if (source == null || source.isEmpty()) {
            return ItemStack.empty();
        }
        return source.clone();
    }
}
