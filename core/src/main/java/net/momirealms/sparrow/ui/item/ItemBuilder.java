package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.LazyItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.item.guard.ItemGuard;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.PlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ItemBuilder {
    // 显示与刷新
    private DisplaySourceFactory source = new DisplaySourceFactory.ProviderFactory(ItemProvider.EMPTY, ItemProvider.EMPTY); // 显示来源声明, 只能配置一次
    private boolean sourceConfigured; // 显示来源是否已完成配置
    private final List<Function<? super Player, ? extends Signal<?>>> dependencies = new ArrayList<>(); // 渲染依赖声明的 signal
    private boolean updateOnClick; // 点击成功后是否主动失效
    // 交互守卫
    private final List<ConfiguredItem.GuardEntry<ItemClick>> clickGuards = new ArrayList<>(); // 点击前置处理器
    private final List<ConfiguredItem.GuardEntry<ItemDragClick>> dragGuards = new ArrayList<>(); // 拖拽前置处理器
    private final List<ConfiguredItem.GuardEntry<BundleSelectClick>> bundleSelectGuards = new ArrayList<>(); // Bundle 前置处理器
    // 交互处理器
    private BiConsumer<Item, ItemClick> clickHandler = (ignoredItem, ignoredClick) -> { };      // 点击处理器
    private BiConsumer<Item, ItemDragClick> dragHandler = (ignoredItem, ignoredDrag) -> { };         // 拖拽处理器
    private BiConsumer<Item, BundleSelectClick> bundleHandler = (ignoredItem, ignoredSelect) -> { }; // Bundle 选择处理器
    // 构建收尾
    private Consumer<ObservableItem> modifier = ignoredItem -> { }; // 构建完成后执行的修改器链

    /**
     * 配置在渲染调用线程立即返回 ItemStack 的显示来源.
     *
     * @param renderer 同步渲染函数, 不得返回 {@code null}
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProvider(@NotNull Function<? super RenderContext, ? extends ItemStack> renderer) {
        return this.setItemProviderAsync(ItemProvider.sync(renderer));
    }

    /**
     * 配置固定显示的物品. 传入的模板会被复制.
     *
     * @param itemStack 固定物品模板
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProviderConstant(@NotNull ItemStack itemStack) {
        return this.setItemProviderAsync(ItemProvider.constant(itemStack));
    }

    /**
     * 配置异步显示来源. Future 未完成时显示最近一次成功结果, 首次完成前显示空物品.
     *
     * @param itemProvider 显示提供器
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProviderAsync(@NotNull ItemProvider itemProvider) {
        this.setSource(new DisplaySourceFactory.ProviderFactory(Objects.requireNonNull(itemProvider, "itemProvider"), ItemProvider.EMPTY));
        return this;
    }

    /**
     * 配置异步显示来源及首次成功前显示的占位物品.
     *
     * @param itemProvider 显示提供器
     * @param placeholder 首次成功结果前显示的占位物品
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProviderAsync(@NotNull ItemProvider itemProvider, @NotNull ItemStack placeholder) {
        return this.setItemProviderAsync(itemProvider, ItemProvider.constant(placeholder));
    }

    /**
     * 配置异步显示来源及首次成功前使用的占位 Provider.
     *
     * @param itemProvider 显示提供器
     * @param placeholder 首次成功结果前使用的占位提供器
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProviderAsync(@NotNull ItemProvider itemProvider, @NotNull ImmediateItemProvider placeholder) {
        this.setSource(new DisplaySourceFactory.ProviderFactory(
                Objects.requireNonNull(itemProvider, "itemProvider"),
                Objects.requireNonNull(placeholder, "placeholder")
        ));
        return this;
    }

    /**
     * 配置首次挂载时解析一次的懒加载显示来源, 解析完成前显示空物品.
     *
     * @param lazyProvider 懒加载显示提供器
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setLazyItemProvider(@NotNull LazyItemProvider lazyProvider) {
        return this.setLazyItemProvider(ItemProvider.EMPTY, lazyProvider);
    }

    /**
     * 配置首次挂载时解析一次的懒加载显示来源.
     * <p>解析结果由同一 Item 的全部挂载共用, 后续挂载不会再次解析.
     *
     * @param placeholder 解析完成前的显示内容
     * @param lazyProvider 懒加载显示提供器
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setLazyItemProvider(@NotNull ItemStack placeholder, @NotNull LazyItemProvider lazyProvider) {
        return this.setLazyItemProvider(
                ItemProvider.constant(Objects.requireNonNull(placeholder, "placeholder")),
                lazyProvider
        );
    }

    /**
     * 配置首次挂载时解析一次的懒加载显示来源.
     * <p>解析结果由同一 Item 的全部挂载共用, 后续挂载不会再次解析.
     * <p><strong>解析 Future 应当及时完成或取消.</strong> 在它完成前, Future 的回调会保留
     * 当前 Item 的懒加载状态与失效通知.
     *
     * @param placeholder 解析完成前的显示内容
     * @param lazyProvider 懒加载显示提供器
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setLazyItemProvider(@NotNull ImmediateItemProvider placeholder, @NotNull LazyItemProvider lazyProvider) {
        this.setSource(new DisplaySourceFactory.LazyFactory(
                Objects.requireNonNull(placeholder, "placeholder"),
                Objects.requireNonNull(lazyProvider, "lazyProvider")
        ));
        return this;
    }

    /**
     * 让 Item 在被显示期间每隔固定 tick 重新渲染一次.
     *
     * @param periodTicks 正数 tick 周期
     * @return 此构建器
     * @throws IllegalArgumentException 当周期不是正数时
     */
    public ItemBuilder updatePeriodically(int periodTicks) {
        return this.dependsOn(Signals.everyTicks(periodTicks));
    }

    /**
     * 声明渲染读取的 Signal. 任一 Signal 失效时重新渲染这个 Item.
     *
     * @param signals 渲染依赖的数据源
     * @return 此构建器
     */
    public ItemBuilder dependsOn(@NotNull Signal<?>... signals) {
        for (int index = 0; index < signals.length; index++) {
            Signal<?> signal = Objects.requireNonNull(signals[index], "signal");
            this.dependencies.add(ignoredViewer -> signal);
        }
        return this;
    }

    /**
     * 声明按查看者 UUID 取值的渲染依赖.
     *
     * @param signal 按玩家分区的数据源
     * @return 此构建器
     */
    public ItemBuilder dependsOn(@NotNull PlayerKeyedSignal<?> signal) {
        Objects.requireNonNull(signal, "signal");
        this.dependencies.add(viewer -> signal.at(viewer.getUniqueId()));
        return this;
    }

    /**
     * 声明通过查看者计算分区键的渲染依赖.
     *
     * @param <K> 分区键类型
     * @param signal 分区数据源
     * @param keyOf 从查看者导出分区 key, 在挂载时执行
     * @return 此构建器
     */
    public <K> ItemBuilder dependsOn(@NotNull KeyedSignal<K, ?> signal, @NotNull Function<? super Player, ? extends K> keyOf) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(keyOf, "keyOf");
        this.dependencies.add(viewer -> signal.at(keyOf.apply(viewer)));
        return this;
    }

    /**
     * 让 Item 在点击守卫全部通过且处理器正常返回后主动失效.
     *
     * @return 此构建器
     */
    public ItemBuilder updateOnClick() {
        this.updateOnClick = true;
        return this;
    }

    /**
     * 添加点击守卫. 守卫按添加顺序执行, 第一个 {@code false} 会终止本次点击.
     *
     * @param guard 点击守卫
     * @return 此构建器
     */
    public ItemBuilder addClickGuard(@NotNull ItemGuard<? super ItemClick> guard) {
        return this.addClickGuard(guard, (ignoredItem, ignoredClick) -> { });
    }

    /**
     * 添加点击守卫与拒绝回调.
     *
     * @param guard 点击守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addClickGuard(@NotNull ItemGuard<? super ItemClick> guard, @NotNull Consumer<? super ItemClick> onRejected) {
        Objects.requireNonNull(onRejected, "onRejected");
        return this.addClickGuard(guard, (ignoredItem, click) -> onRejected.accept(click));
    }

    /**
     * 添加可访问 Item 自身的点击守卫与拒绝回调.
     *
     * @param guard 点击守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addClickGuard(@NotNull ItemGuard<? super ItemClick> guard, @NotNull BiConsumer<? super Item, ? super ItemClick> onRejected) {
        this.clickGuards.add(new ConfiguredItem.GuardEntry<>(
                Objects.requireNonNull(guard, "guard"),
                Objects.requireNonNull(onRejected, "onRejected")
        ));
        return this;
    }

    /**
     * 添加点击处理器. 处理器按添加顺序执行.
     *
     * @param clickHandler 点击处理器
     * @return 此构建器
     */
    public ItemBuilder addClickHandler(@NotNull Consumer<? super ItemClick> clickHandler) {
        return this.addClickHandler((ignoredItem, click) -> clickHandler.accept(click));
    }

    /**
     * 添加可访问 Item 自身的点击处理器.
     *
     * @param clickHandler 同时接收物品和点击事件的处理器
     * @return 此构建器
     */
    public ItemBuilder addClickHandler(@NotNull BiConsumer<? super Item, ? super ItemClick> clickHandler) {
        this.clickHandler = this.clickHandler.andThen(clickHandler);
        return this;
    }

    /**
     * 添加拖拽守卫.
     *
     * @param guard 拖拽守卫
     * @return 此构建器
     */
    public ItemBuilder addDragGuard(@NotNull ItemGuard<? super ItemDragClick> guard) {
        return this.addDragGuard(guard, (ignoredItem, ignoredDrag) -> { });
    }

    /**
     * 添加拖拽守卫与拒绝回调.
     *
     * @param guard 拖拽守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addDragGuard(@NotNull ItemGuard<? super ItemDragClick> guard, @NotNull Consumer<? super ItemDragClick> onRejected) {
        Objects.requireNonNull(onRejected, "onRejected");
        return this.addDragGuard(guard, (ignoredItem, drag) -> onRejected.accept(drag));
    }

    /**
     * 添加可访问 Item 自身的拖拽守卫与拒绝回调.
     *
     * @param guard 拖拽守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addDragGuard(@NotNull ItemGuard<? super ItemDragClick> guard, @NotNull BiConsumer<? super Item, ? super ItemDragClick> onRejected) {
        this.dragGuards.add(new ConfiguredItem.GuardEntry<>(
                Objects.requireNonNull(guard, "guard"),
                Objects.requireNonNull(onRejected, "onRejected")
        ));
        return this;
    }

    /**
     * 添加拖拽处理器.
     *
     * @param dragHandler 拖拽处理器
     * @return 此构建器
     */
    public ItemBuilder addDragHandler(@NotNull Consumer<? super ItemDragClick> dragHandler) {
        return this.addDragHandler((ignoredItem, drag) -> dragHandler.accept(drag));
    }

    /**
     * 添加可访问 Item 自身的拖拽处理器.
     *
     * @param dragHandler 同时接收物品和拖拽事件的处理器
     * @return 此构建器
     */
    public ItemBuilder addDragHandler(@NotNull BiConsumer<? super Item, ? super ItemDragClick> dragHandler) {
        this.dragHandler = this.dragHandler.andThen(dragHandler);
        return this;
    }

    /**
     * 添加 Bundle 选择守卫.
     *
     * @param guard Bundle 选择守卫
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectGuard(@NotNull ItemGuard<? super BundleSelectClick> guard) {
        return this.addBundleSelectGuard(guard, (ignoredItem, ignoredSelect) -> { });
    }

    /**
     * 添加 Bundle 选择守卫与拒绝回调.
     *
     * @param guard Bundle 选择守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectGuard(@NotNull ItemGuard<? super BundleSelectClick> guard, @NotNull Consumer<? super BundleSelectClick> onRejected) {
        Objects.requireNonNull(onRejected, "onRejected");
        return this.addBundleSelectGuard(guard, (ignoredItem, select) -> onRejected.accept(select));
    }

    /**
     * 添加可访问 Item 自身的 Bundle 选择守卫与拒绝回调.
     *
     * @param guard Bundle 选择守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectGuard(@NotNull ItemGuard<? super BundleSelectClick> guard, @NotNull BiConsumer<? super Item, ? super BundleSelectClick> onRejected) {
        this.bundleSelectGuards.add(new ConfiguredItem.GuardEntry<>(
                Objects.requireNonNull(guard, "guard"),
                Objects.requireNonNull(onRejected, "onRejected")
        ));
        return this;
    }

    /**
     * 添加 Bundle 选择处理器. 处理器按添加顺序执行.
     *
     * @param selectHandler 选择处理器
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectHandler(@NotNull Consumer<? super BundleSelectClick> selectHandler) {
        return this.addBundleSelectHandler((ignoredItem, select) -> selectHandler.accept(select));
    }

    /**
     * 添加可访问 Item 自身的 Bundle 选择处理器.
     *
     * @param selectHandler 同时接收物品和选择事件的处理器
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectHandler(@NotNull BiConsumer<? super Item, ? super BundleSelectClick> selectHandler) {
        this.bundleHandler = this.bundleHandler.andThen(selectHandler);
        return this;
    }

    /**
     * 添加构建后修改器. 修改器按添加顺序执行.
     * <p>修改器抛出的异常由 {@link #build()} 直接抛出, 后续修改器不再执行.
     *
     * @param modifier 构建完成后的修改器
     * @return 此构建器
     */
    public ItemBuilder addModifier(@NotNull Consumer<? super ObservableItem> modifier) {
        this.modifier = this.modifier.andThen(modifier);
        return this;
    }

    /**
     * 构建具备主动通知能力的 Item.
     *
     * @return 构建完成的 ObservableItem
     */
    public ObservableItem build() {
        ObservableItem item = new ConfiguredItem(
                this.source,
                this.dependencies,
                this.clickGuards,
                this.dragGuards,
                this.bundleSelectGuards,
                this.clickHandler,
                this.dragHandler,
                this.bundleHandler,
                this.updateOnClick
        );
        this.modifier.accept(item);
        return item;
    }

    // 默认空来源不占用配置次数, 显式来源只能设置一次.
    private void setSource(DisplaySourceFactory source) {
        if (this.sourceConfigured)
            throw new IllegalStateException("display source has already been configured");
        this.source = source;
        this.sourceConfigured = true;
    }

    // 每次 build 都从声明创建独立的运行时显示来源.
    sealed interface DisplaySourceFactory permits DisplaySourceFactory.ProviderFactory, DisplaySourceFactory.LazyFactory {

        DisplaySource create(Runnable invalidator);

        record ProviderFactory(ItemProvider provider, ImmediateItemProvider placeholder) implements DisplaySourceFactory {

            @Override
            public DisplaySource create(Runnable invalidator) {
                // 固定来源没有解析完成事件, 不需要失效回调.
                return new DisplaySource.FixedDisplaySource(this.provider, this.placeholder);
            }
        }

        record LazyFactory(ImmediateItemProvider placeholder, LazyItemProvider lazyProvider) implements DisplaySourceFactory {

            @Override
            public DisplaySource create(Runnable invalidator) {
                return new DisplaySource.LazyDisplaySource(this.placeholder, this.lazyProvider, invalidator);
            }
        }
    }

    // 运行时显示来源, 同时负责当前 Provider 与首次挂载行为.
    sealed interface DisplaySource permits DisplaySource.FixedDisplaySource, DisplaySource.LazyDisplaySource {

        ItemProvider provider();

        ImmediateItemProvider placeholder();

        // 懒加载来源在这里启动首次解析.
        default void onAttached() {
        }

        record FixedDisplaySource(@NotNull ItemProvider provider, @NotNull ImmediateItemProvider placeholder) implements DisplaySource {
            public FixedDisplaySource {
                Objects.requireNonNull(provider, "provider");
                Objects.requireNonNull(placeholder, "placeholder");
            }
        }

        // 首次挂载时解析一次, 后续挂载复用同一结果.
        final class LazyDisplaySource implements DisplaySource {
            private final ImmediateItemProvider placeholder;
            // getAndSet(null) 让多个并发挂载中只有一个能启动解析.
            private final AtomicReference<LazyItemProvider> pendingProvider;
            private final Runnable invalidator;
            // 完成回调可能运行在异步线程, 渲染线程需要立即看到新 Provider.
            private volatile ItemProvider currentProvider;

            LazyDisplaySource(ImmediateItemProvider placeholder, LazyItemProvider lazyProvider, Runnable invalidator) {
                this.placeholder = Objects.requireNonNull(placeholder, "placeholder");
                this.currentProvider = placeholder;
                this.pendingProvider = new AtomicReference<>(Objects.requireNonNull(lazyProvider, "lazyProvider"));
                this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
            }

            @Override
            public ItemProvider provider() {
                return this.currentProvider;
            }

            @Override
            public ImmediateItemProvider placeholder() {
                return this.placeholder;
            }

            @Override
            public void onAttached() {
                LazyItemProvider lazyProvider = this.pendingProvider.getAndSet(null);
                if (lazyProvider == null) return;

                // resolve 同步抛出也按解析失败处理.
                CompletableFuture<? extends ItemProvider> stage;
                try {
                    stage = Objects.requireNonNull(lazyProvider.resolve(), "lazyProvider result");
                } catch (Throwable throwable) {
                    SparrowUI.getInstance().handleException("Failed to resolve lazy item provider", throwable);
                    return;
                }

                stage.whenComplete((provider, throwable) -> {
                    // 失败时保留占位 Provider.
                    if (throwable != null) {
                        SparrowUI.getInstance().handleException("Failed to resolve lazy item provider", ThrowableUtils.unwrapCompletion(throwable));
                        return;
                    }
                    if (provider == null) {
                        SparrowUI.getInstance().handleException("Failed to resolve lazy item provider", new NullPointerException("resolved provider"));
                        return;
                    }

                    // 先发布新 Provider, 再通知 Window 读取它.
                    this.currentProvider = provider;
                    try {
                        this.invalidator.run();
                    } catch (RuntimeException exception) {
                        // 通知失败不撤销已经发布的 Provider.
                        SparrowUI.getInstance().handleException("Failed to invalidate windows for lazy item", exception);
                    }
                });
            }
        }

    }
}
