package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 持有固定模板并为每次渲染返回独立物品快照的提供器.
 */
final class ItemWrapper implements ItemProvider {
    private final ItemStack template;

    ItemWrapper(@NotNull ItemStack template) {
        this.template = ItemUtils.copyOrEmpty(template);
    }

    @Override
    public ItemStack provide(@NotNull RenderContext context) {
        return ItemUtils.copyOrEmpty(template);
    }
}
