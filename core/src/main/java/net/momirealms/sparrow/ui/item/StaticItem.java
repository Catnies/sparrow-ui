package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.click.BundleSelectClick;
import net.momirealms.sparrow.ui.click.ItemClick;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 不会主动更新的 Item.
 * ItemProvider 可以根据渲染上下文生成不同结果, 但该 Item 本身不持有主动变化和刷新的权利.
 */
public final class StaticItem implements Item {
    private final ItemProvider itemProvider; // 显示提供器
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler; // null 表示不处理点击
    private final BiConsumer<? super Item, ? super BundleSelectClick> bundleSelectHandler; // null 表示不处理 Bundle 选择

    /**
     * 创建以固定物品堆显示、无交互处理器的静态 Item.
     *
     * @param itemStack 固定显示的物品堆
     */
    public StaticItem(@NotNull ItemStack itemStack) {
        this(ItemProvider.constant(itemStack), null);
    }

    /**
     * 创建只持有显示来源、无交互处理器的静态 Item.
     *
     * @param itemProvider 显示提供器
     */
    public StaticItem(@NotNull ItemProvider itemProvider) {
        this(itemProvider, null);
    }

    /**
     * 创建带点击处理器、无 Bundle 选择处理器的静态 Item.
     *
     * @param itemProvider 显示提供器
     * @param clickHandler 点击处理器, 可为 {@code null}
     */
    public StaticItem(
            @NotNull ItemProvider itemProvider,
            @Nullable BiConsumer<? super Item, ? super ItemClick> clickHandler
    ) {
        this(itemProvider, clickHandler, null);
    }

    /**
     * 创建带完整交互处理器的静态 Item.
     *
     * @param itemProvider 显示提供器
     * @param clickHandler 点击处理器, 可为 {@code null}
     * @param bundleSelectHandler Bundle 选择处理器, 可为 {@code null}
     */
    public StaticItem(
            @NotNull ItemProvider itemProvider,
            @Nullable BiConsumer<? super Item, ? super ItemClick> clickHandler,
            @Nullable BiConsumer<? super Item, ? super BundleSelectClick> bundleSelectHandler
    ) {
        this.itemProvider = Objects.requireNonNull(itemProvider, "itemProvider");
        this.clickHandler = clickHandler;
        this.bundleSelectHandler = bundleSelectHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ItemProvider getItemProvider() {
        return this.itemProvider;
    }

    /**
     * {@inheritDoc}
     *
     * <p>未配置点击处理器时不产生任何效果.
     */
    @Override
    public void handleClick(ItemClick click) {
        if (this.clickHandler != null) {
            this.clickHandler.accept(this, Objects.requireNonNull(click, "click"));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>未配置 Bundle 选择处理器时不产生任何效果.
     */
    @Override
    public void handleBundleSelect(BundleSelectClick select) {
        if (this.bundleSelectHandler != null) {
            this.bundleSelectHandler.accept(this, Objects.requireNonNull(select, "select"));
        }
    }
}
