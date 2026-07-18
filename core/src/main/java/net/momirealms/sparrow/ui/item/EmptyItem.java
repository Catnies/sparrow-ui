package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;

final class EmptyItem implements Item {

    @Override
    public ItemProvider getItemProvider() {
        return ItemProvider.EMPTY;
    }
}
