package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;

/**
 * 使用固定 {@link ItemProvider} 的 {@link Item} 实现.
 */
public final class ConstItem implements Item {
    private final ItemProvider itemProvider; // 固定的 ItemProvider

    public ConstItem(ItemProvider itemProvider) {
        this.itemProvider = itemProvider;
    }

    @Override
    public ItemProvider getItemProvider() {
        return itemProvider;
    }

    @Override
    public void handleClick(ItemClick click) {
    }
}
