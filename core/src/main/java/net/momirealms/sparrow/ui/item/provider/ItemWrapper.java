package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.util.ItemSnapshots;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

final class ItemWrapper implements ItemProvider {
    private final ItemStack template;

    ItemWrapper(@NotNull ItemStack template) {
        this.template = ItemSnapshots.copyOrEmpty(template);
    }

    @Override
    public ItemStack provide(@NotNull RenderContext context) {
        return ItemSnapshots.copyOrEmpty(template);
    }
}
