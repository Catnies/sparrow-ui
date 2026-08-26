package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDrag;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.ObservableDispatcher;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.guard.ItemGuard;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class ConfiguredItem implements ObservableItem {
    // 显示与失效
    private final ItemBuilder.DisplaySource displaySource; // 显示来源, 决定渲染提供器与挂载行为
    private final List<Function<? super Player, ? extends Signal<?>>> dependencies; // 构建器声明的依赖, 每次挂载按查看者解析
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>(); // 挂载观察者注册表, 负责广播失效
    // 交互守卫
    @Nullable private final ItemGuard<ItemClick> clickGuard; // 点击前置处理器链
    @Nullable private final ItemGuard<ItemDrag> dragGuard; // 拖拽前置处理器链
    @Nullable private final ItemGuard<BundleSelectClick> bundleSelectGuard; // Bundle 选择前置处理器链
    // 交互处理器
    @Nullable private final BiConsumer<Item, ItemClick> clickHandler;     // 点击处理器
    @Nullable private final BiConsumer<Item, ItemDrag> dragHandler;       // 拖拽处理器
    @Nullable private final BiConsumer<Item, BundleSelectClick> bundleHandler; // Bundle 选择处理器
    private final boolean updateOnClick; // 点击成功后是否主动失效

    ConfiguredItem(
            @NotNull ItemBuilder.DisplaySourceFactory source,
            @NotNull List<? extends Function<? super Player, ? extends Signal<?>>> dependencies,
            @Nullable ItemGuard<ItemClick> clickGuard,
            @Nullable ItemGuard<ItemDrag> dragGuard,
            @Nullable ItemGuard<BundleSelectClick> bundleSelectGuard,
            @Nullable BiConsumer<Item, ItemClick> clickHandler,
            @Nullable BiConsumer<Item, ItemDrag> dragHandler,
            @Nullable BiConsumer<Item, BundleSelectClick> bundleHandler,
            boolean updateOnClick
    ) {
        this.displaySource = source.create(this::notifyWindows);
        this.dependencies = List.copyOf(dependencies);
        this.clickGuard = clickGuard;
        this.dragGuard = dragGuard;
        this.bundleSelectGuard = bundleSelectGuard;
        this.clickHandler = clickHandler;
        this.dragHandler = dragHandler;
        this.bundleHandler = bundleHandler;
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
    public void handleClick(@NotNull ItemClick click) {
        if (this.clickGuard != null && !this.clickGuard.test(this, click)) return;
        if (this.clickHandler != null) {
            this.clickHandler.accept(this, click);
        }
        if (this.updateOnClick) {
            this.notifyWindows();
        }
    }

    @Override
    public void handleDrag(@NotNull ItemDrag drag) {
        if (this.dragGuard != null && !this.dragGuard.test(this, drag)) return;
        if (this.dragHandler != null) {
            this.dragHandler.accept(this, drag);
        }
    }

    @Override
    public void handleBundleSelect(@NotNull BundleSelectClick select) {
        if (this.bundleSelectGuard != null && !this.bundleSelectGuard.test(this, select)) return;
        if (this.bundleHandler != null) {
            this.bundleHandler.accept(this, select);
        }
    }

    @Override
    public ItemAttachment attach(@NotNull Window window, @NotNull Observer<? super Item> observer) {
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
}
