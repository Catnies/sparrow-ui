package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;

@FunctionalInterface
public interface ItemProvider {
    ItemProvider EMPTY = ignoredContext -> ItemUtils.copyOrEmpty(null);

    /**
     * 渲染本次要显示的物品.
     * <p><strong>不得改动 Window、GUI、Inventory, 也不得额外请求刷新或同步.</strong>
     *
     * @param context 当前渲染上下文
     * @return 本次渲染要显示的物品
     */
    ItemStack provide(RenderContext context);

    /**
     * 基于固定物品的渲染器.
     *
     * @param template 模板物品堆
     * @return 提供器
     */
    static ItemProvider constant(ItemStack template) {
        return new ItemWrapper(template);
    }
}
