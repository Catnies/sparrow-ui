package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.BundleSelect;
import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 不会主动更新的 Item.
 * ItemProvider 可以根据渲染上下文生成不同结果, 但该 Item 本身不持有主动变化和刷新的权利.
 */
public final class StaticItem implements Item {
    private final ItemProvider itemProvider;
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler; // null 表示不处理点击
    private final BiConsumer<? super Item, ? super BundleSelect> bundleSelectHandler; // null 表示不处理 Bundle 选择

    /**
     * 创建只持有显示来源的静态 Item.
     *
     * @param itemProvider 显示来源
     */
    public StaticItem(@NotNull ItemProvider itemProvider) {
        this(itemProvider, null);
    }

    /**
     * 创建带点击处理器的静态 Item.
     *
     * @param itemProvider 显示来源
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
     * @param itemProvider 显示来源
     * @param clickHandler 点击处理器, 可为 {@code null}
     * @param bundleSelectHandler Bundle 选择处理器, 可为 {@code null}
     */
    public StaticItem(
            @NotNull ItemProvider itemProvider,
            @Nullable BiConsumer<? super Item, ? super ItemClick> clickHandler,
            @Nullable BiConsumer<? super Item, ? super BundleSelect> bundleSelectHandler
    ) {
        this.itemProvider = Objects.requireNonNull(itemProvider, "itemProvider");
        this.clickHandler = clickHandler;
        this.bundleSelectHandler = bundleSelectHandler;
    }

    @Override
    public ItemProvider getItemProvider() {
        return itemProvider;
    }

    @Override
    public void handleClick(ItemClick click) {
        if (this.clickHandler != null) {
            this.clickHandler.accept(this, Objects.requireNonNull(click, "click"));
        }
    }

    @Override
    public void handleBundleSelect(BundleSelect select) {
        if (this.bundleSelectHandler != null) {
            this.bundleSelectHandler.accept(this, Objects.requireNonNull(select, "select"));
        }
    }
}
