package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 持有固定模板并为每次渲染返回独立物品快照的提供器.
 */
final class ItemWrapper implements ItemProvider {
    private final ItemStack template;

    /**
     * 创建包装固定模板的提供器, 构造时对模板做一次防御性复制.
     *
     * @param template 模板物品堆
     */
    ItemWrapper(@NotNull ItemStack template) {
        this.template = ItemUtils.copyOrEmpty(template);
    }

    /**
     * {@inheritDoc}
     *
     * <p>每次渲染都返回模板的独立副本, 调用方修改结果不会影响后续渲染.
     */
    @Override
    public ItemStack provide(@NotNull RenderContext context) {
        return ItemUtils.copyOrEmpty(this.template);
    }
}
