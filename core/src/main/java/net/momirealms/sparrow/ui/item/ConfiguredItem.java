package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.BundleSelect;
import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 组合显示来源、失效传播、周期计划和交互行为的内部 Item 实现.
 */
final class ConfiguredItem implements ObservableItem {
    private final ItemBuilder.DisplaySource displaySource;
    private final RefreshPlan refreshPlan;
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler;
    private final BiConsumer<? super Item, ? super BundleSelect> bundleHandler;
    private final boolean updateOnClick;
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>();

    ConfiguredItem(
            @NotNull ItemBuilder.DisplayFactory displayFactory,
            @NotNull RefreshPlan explicitRefreshPlan,
            @NotNull BiConsumer<? super Item, ? super ItemClick> clickHandler,
            @NotNull BiConsumer<? super Item, ? super BundleSelect> bundleHandler,
            boolean updateOnClick
    ) {
        ItemBuilder.DisplayFactory checkedFactory = Objects.requireNonNull(displayFactory, "displayFactory");
        this.displaySource = Objects.requireNonNull(checkedFactory.create(this::notifyWindows), "displayFactory result");
        this.refreshPlan = this.displaySource.refreshPlan().or(explicitRefreshPlan);
        this.clickHandler = Objects.requireNonNull(clickHandler, "clickHandler");
        this.bundleHandler = Objects.requireNonNull(bundleHandler, "bundleHandler");
        this.updateOnClick = updateOnClick;
    }

    @Override
    public ItemProvider getItemProvider() {
        return this.displaySource.provider();
    }

    @Override
    public void handleClick(ItemClick click) {
        this.clickHandler.accept(this, click);
        if (this.updateOnClick) {
            this.notifyWindows();
        }
    }

    @Override
    public void handleBundleSelect(@NotNull BundleSelect select) {
        this.bundleHandler.accept(this, select);
    }

    @Override
    public ItemAttachment attach(@NotNull Observer<? super Item> observer) {
        Subscription subscription = this.observers.subscribe(observer);
        try {
            this.displaySource.onAttached();
            return ItemAttachment.subscribed(this.refreshPlan, subscription);
        } catch (RuntimeException | Error throwable) {
            subscription.close();
            throw throwable;
        }
    }

    @Override
    public void notifyWindows() {
        this.observers.publish(this);
    }
}
