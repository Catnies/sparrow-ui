package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * 不会主动更新的 Item.
 * ItemProvider 可以根据渲染上下文生成不同结果, 但该 Item 本身不持有主动变化和刷新的权利.
 */
public final class StaticItem implements Item {
    private final ItemProvider itemProvider;
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler;

    public StaticItem(ItemProvider itemProvider) {
        this(itemProvider, null);
    }

    public StaticItem(@NotNull ItemProvider itemProvider, @Nullable BiConsumer<? super Item, ? super ItemClick> clickHandler) {
        this.itemProvider = itemProvider;
        this.clickHandler = clickHandler;
    }

    @Override
    public ItemProvider getItemProvider() {
        return itemProvider;
    }

    @Override
    public void handleClick(ItemClick click) {
        if (this.clickHandler != null) {
            this.clickHandler.accept(this, click);
        }
    }
}
