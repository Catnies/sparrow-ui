package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.item.click.ItemInteraction;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.guard.ItemGuard;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

final class ConfiguredItem implements ObservableItem {
    private final ItemBuilder.DisplaySource displaySource; // 显示来源, 决定渲染提供器与自带刷新计划
    private final RefreshPlan refreshPlan; // 显示来源自带计划与显式计划合并后的周期刷新计划
    private final List<GuardEntry<ItemClick>> clickGuards; // 点击前置处理器
    private final List<GuardEntry<ItemDragClick>> dragGuards; // 拖拽前置处理器
    private final List<GuardEntry<BundleSelectClick>> bundleSelectGuards; // Bundle 选择前置处理器
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler;     // 点击处理器
    private final BiConsumer<? super Item, ? super ItemDragClick> dragHandler;       // 拖拽处理器
    private final BiConsumer<? super Item, ? super BundleSelectClick> bundleHandler; // Bundle 选择处理器
    private final boolean updateOnClick; // 点击成功后是否主动失效
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>(); // 挂载观察者注册表, 负责广播失效

    ConfiguredItem(
            @NotNull ItemBuilder.SourceSpec source,
            @NotNull RefreshPlan explicitRefreshPlan,
            @NotNull List<? extends GuardEntry<ItemClick>> clickGuards,
            @NotNull List<? extends GuardEntry<ItemDragClick>> dragGuards,
            @NotNull List<? extends GuardEntry<BundleSelectClick>> bundleSelectGuards,
            @NotNull BiConsumer<? super Item, ? super ItemClick> clickHandler,
            @NotNull BiConsumer<? super Item, ? super ItemDragClick> dragHandler,
            @NotNull BiConsumer<? super Item, ? super BundleSelectClick> bundleHandler,
            boolean updateOnClick
    ) {
        this.displaySource = Objects.requireNonNull(source.create(this::notifyWindows), "source result");
        // 合并显示来源自带的刷新计划(如轮播帧周期)与构建器显式配置的计划
        this.refreshPlan = this.displaySource.refreshPlan().or(explicitRefreshPlan);
        this.clickGuards = List.copyOf(clickGuards);
        this.dragGuards = List.copyOf(dragGuards);
        this.bundleSelectGuards = List.copyOf(bundleSelectGuards);
        this.clickHandler = Objects.requireNonNull(clickHandler, "clickHandler");
        this.dragHandler = Objects.requireNonNull(dragHandler, "dragHandler");
        this.bundleHandler = Objects.requireNonNull(bundleHandler, "bundleHandler");
        this.updateOnClick = updateOnClick;
    }

    @Override
    public ItemProvider getItemProvider() {
        return this.displaySource.provider();
    }

    @Override
    public void handleClick(ItemClick click) {
        if (!this.passes(this.clickGuards, click)) return;
        this.clickHandler.accept(this, click);
        if (this.updateOnClick) {
            this.notifyWindows();
        }
    }

    @Override
    public void handleDrag(ItemDragClick drag) {
        if (!this.passes(this.dragGuards, drag)) return;
        this.dragHandler.accept(this, drag);
    }

    @Override
    public void handleBundleSelect(@NotNull BundleSelectClick select) {
        if (!this.passes(this.bundleSelectGuards, select)) return;
        this.bundleHandler.accept(this, select);
    }

    private <C extends ItemInteraction> boolean passes(@NotNull List<GuardEntry<C>> guards, @NotNull C interaction) {
        for (int index = 0; index < guards.size(); index++) {
            GuardEntry<C> entry = guards.get(index);
            if (!entry.guard().test(this, interaction)) {
                entry.onRejected().accept(this, interaction);
                return false;
            }
        }
        return true;
    }

    // 登记观察者并触发显示来源的首次挂载回调(如启动异步加载); 回调失败时回滚订阅, 避免观察者泄漏.
    @Override
    public ItemAttachment attach(@NotNull Observer<? super Item> observer) {
        Subscription subscription = this.observers.subscribe(observer);
        try {
            this.displaySource.onAttached();
            return ItemAttachment.subscribed(this.refreshPlan, subscription);
        } catch (RuntimeException | Error throwable) {
            // onAttached 失败时回滚订阅, 避免观察者泄漏.
            subscription.close();
            throw throwable;
        }
    }

    @Override
    public void notifyWindows() {
        this.observers.publish(this);
    }

    record GuardEntry<C extends ItemInteraction>(
            @NotNull ItemGuard<? super C> guard,
            @NotNull BiConsumer<? super Item, ? super C> onRejected
    ) {
    }
}
