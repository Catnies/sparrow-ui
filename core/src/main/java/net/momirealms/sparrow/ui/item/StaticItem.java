package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * 使用固定 Provider, 并可选处理点击与 Bundle 选择的 Item.
 */
public final class StaticItem implements Item {
    private final ItemProvider itemProvider;
    private final BiConsumer<Item, ItemClick> clickHandler;
    private final BiConsumer<Item, BundleSelectClick> bundleSelectHandler;

    /**
     * 创建显示固定物品的 Item. 传入的物品会被复制.
     *
     * @param itemStack 显示模板
     */
    public StaticItem(@NotNull ItemStack itemStack) {
        this(ItemProvider.constant(itemStack), null);
    }

    /**
     * 创建只负责显示的 Item.
     *
     * @param itemProvider 显示内容来源
     */
    public StaticItem(@NotNull ItemProvider itemProvider) {
        this(itemProvider, null);
    }

    /**
     * 创建可处理点击的 Item.
     *
     * @param itemProvider 显示内容来源
     * @param clickHandler 点击处理器, {@code null} 表示忽略点击
     */
    public StaticItem(
            @NotNull ItemProvider itemProvider,
            @Nullable BiConsumer<Item, ItemClick> clickHandler
    ) {
        this(itemProvider, clickHandler, null);
    }

    /**
     * 创建可处理点击与 Bundle 选择的 Item.
     *
     * @param itemProvider 显示内容来源
     * @param clickHandler 点击处理器, {@code null} 表示忽略点击
     * @param bundleSelectHandler Bundle 选择处理器, {@code null} 表示忽略选择
     */
    public StaticItem(
            @NotNull ItemProvider itemProvider,
            @Nullable BiConsumer<Item, ItemClick> clickHandler,
            @Nullable BiConsumer<Item, BundleSelectClick> bundleSelectHandler
    ) {
        this.itemProvider = itemProvider;
        this.clickHandler = clickHandler;
        this.bundleSelectHandler = bundleSelectHandler;
    }

    @NotNull
    @Override
    public ItemProvider getItemProvider() {
        return this.itemProvider;
    }

    @Override
    public void handleClick(@NotNull ItemClick click) {
        if (this.clickHandler != null) {
            this.clickHandler.accept(this, click);
        }
    }

    @Override
    public void handleBundleSelect(@NotNull BundleSelectClick select) {
        if (this.bundleSelectHandler != null) {
            this.bundleSelectHandler.accept(this, select);
        }
    }
}
