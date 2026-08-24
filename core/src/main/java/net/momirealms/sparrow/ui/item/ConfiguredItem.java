package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.item.click.ItemInteraction;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.guard.ItemGuard;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class ConfiguredItem implements ObservableItem {
    private final ItemBuilder.DisplaySource displaySource; // 显示来源, 决定渲染提供器与挂载行为
    private final List<GuardEntry<ItemClick>> clickGuards; // 点击前置处理器
    private final List<GuardEntry<ItemDragClick>> dragGuards; // 拖拽前置处理器
    private final List<GuardEntry<BundleSelectClick>> bundleSelectGuards; // Bundle 选择前置处理器
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler;     // 点击处理器
    private final BiConsumer<? super Item, ? super ItemDragClick> dragHandler;       // 拖拽处理器
    private final BiConsumer<? super Item, ? super BundleSelectClick> bundleHandler; // Bundle 选择处理器
    private final boolean updateOnClick; // 点击成功后是否主动失效
    private final List<Function<? super Player, ? extends Signal<?>>> dependencies; // 构建器声明的依赖, 每次挂载按查看者解析
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>(); // 挂载观察者注册表, 负责广播失效

    ConfiguredItem(
            @NotNull ItemBuilder.DisplaySourceFactory source,
            @NotNull List<? extends Function<? super Player, ? extends Signal<?>>> dependencies,
            @NotNull List<? extends GuardEntry<ItemClick>> clickGuards,
            @NotNull List<? extends GuardEntry<ItemDragClick>> dragGuards,
            @NotNull List<? extends GuardEntry<BundleSelectClick>> bundleSelectGuards,
            @NotNull BiConsumer<? super Item, ? super ItemClick> clickHandler,
            @NotNull BiConsumer<? super Item, ? super ItemDragClick> dragHandler,
            @NotNull BiConsumer<? super Item, ? super BundleSelectClick> bundleHandler,
            boolean updateOnClick
    ) {
        this.displaySource = Objects.requireNonNull(source.create(this::notifyWindows), "source result");
        this.dependencies = List.copyOf(dependencies);
        this.clickGuards = List.copyOf(clickGuards);
        this.dragGuards = List.copyOf(dragGuards);
        this.bundleSelectGuards = List.copyOf(bundleSelectGuards);
        this.clickHandler = Objects.requireNonNull(clickHandler, "clickHandler");
        this.dragHandler = Objects.requireNonNull(dragHandler, "dragHandler");
        this.bundleHandler = Objects.requireNonNull(bundleHandler, "bundleHandler");
        this.updateOnClick = updateOnClick;
    }

    @NotNull
    @Override
    public ItemProvider getItemProvider() {
        return this.displaySource.provider();
    }

    @NotNull
    @Override
    public ImmediateItemProvider getPlaceholder() {
        return this.displaySource.placeholder();
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

    // 首个拒绝交互的守卫负责执行自己的拒绝回调.
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

    @Override
    public ItemAttachment attach(@NotNull Window window, @NotNull Observer<? super Item> observer) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(observer, "observer");
        ItemAttachment.Tracking attachment = ItemAttachment.tracking(this, observer);
        // 观察者, 依赖与懒加载来源全部就绪后, 本次挂载才算成功.
        try {
            attachment.track(this.observers.subscribe(observer));
            attachment.subscribeDependencies(this.dependencies, window.viewer());
            this.displaySource.onAttached();
            return attachment;
        } catch (RuntimeException | Error throwable) {
            // 保留原始挂载异常, 清理异常作为补充信息.
            try {
                attachment.close();
            } catch (RuntimeException | Error closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
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
