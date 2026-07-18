package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;

public interface Item {

    Item EMPTY = new EmptyItem();

    /**
     * 获取 {@link ItemProvider}.
     *
     * @return 此 Item 使用的 Provider
     */
    ItemProvider getItemProvider();

    /**
     * 处理玩家点击物品事件.
     *
     * @param click 点击事件上下文
     */
    void handleClick(ItemClick click);

}
