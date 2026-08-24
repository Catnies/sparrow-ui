package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

final class ItemWrapper implements ImmediateItemProvider {
    private final ItemStack template;

    ItemWrapper(@NotNull ItemStack template) {
        this.template = ItemUtils.copyOrEmpty(template);
    }

    @NotNull
    @Override
    public ItemStack provideImmediately(@NotNull RenderContext context) {
        return this.template;
    }
}
