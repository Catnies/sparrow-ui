package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;

/**
 * 空 Item 的单例实现, 显示为空物品堆且不响应任何交互.
 */
final class EmptyItem implements Item {

    /**
     * {@inheritDoc}
     */
    @Override
    public ItemProvider getItemProvider() {
        return ItemProvider.EMPTY;
    }
}
