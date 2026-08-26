package net.momirealms.sparrow.ui.item.provider;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

final class ItemWrapper implements ImmediateItemProvider {
    private final ItemStack template;

    ItemWrapper(@NotNull ItemStack template) {
        this.template = template.clone();
    }

    @NotNull
    @Override
    public ItemStack provideImmediately(@NotNull RenderContext context) {
        return this.template;
    }
}
