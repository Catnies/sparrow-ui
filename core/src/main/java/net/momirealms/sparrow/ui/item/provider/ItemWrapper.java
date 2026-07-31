package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

final class ItemWrapper implements ItemProvider {
    private final ItemStack template;

    /**
     * 创建包装固定模板的提供器.
     *
     * @param template 模板物品堆
     */
    ItemWrapper(@NotNull ItemStack template) {
        this.template = ItemUtils.copyOrEmpty(template);
    }

    @Override
    public ItemStack provide(@NotNull RenderContext context) {
        return ItemUtils.copyOrEmpty(this.template);
    }
}
