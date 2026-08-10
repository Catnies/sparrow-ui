package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.item.provider.AsyncItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.LazyItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.item.guard.ItemGuard;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class ItemBuilder {
    private SourceSpec source = new SourceSpec.ProviderSpec(ItemProvider.EMPTY); // 显示来源声明, 只能配置一次
    private boolean sourceConfigured; // 显示来源是否已完成配置

    private final List<ConfiguredItem.GuardEntry<ItemClick>> clickGuards = new ArrayList<>(); // 点击前置处理器
    private final List<ConfiguredItem.GuardEntry<ItemDragClick>> dragGuards = new ArrayList<>(); // 拖拽前置处理器
    private final List<ConfiguredItem.GuardEntry<BundleSelectClick>> bundleSelectGuards = new ArrayList<>(); // Bundle 前置处理器
    private BiConsumer<Item, ItemClick> clickHandler = (ignoredItem, ignoredClick) -> { };      // 点击处理器
    private BiConsumer<Item, ItemDragClick> dragHandler = (ignoredItem, ignoredDrag) -> { };         // 拖拽处理器
    private BiConsumer<Item, BundleSelectClick> bundleHandler = (ignoredItem, ignoredSelect) -> { }; // Bundle 选择处理器
    private Consumer<ObservableItem> modifier = ignoredItem -> { }; // 构建完成后执行的修改器链
    private RefreshPlan explicitRefreshPlan = RefreshPlan.none();   // 显式配置的周期刷新计划
    private boolean updateOnClick; // 点击成功后是否主动失效

    /**
     * 配置固定或依赖 RenderContext 的 ItemProvider.
     *
     * @param itemProvider 显示提供器
     * @return 此构建器
     */
    public ItemBuilder setItemProvider(@NotNull ItemProvider itemProvider) {
        this.setSource(new SourceSpec.ProviderSpec(Objects.requireNonNull(itemProvider, "itemProvider")));
        return this;
    }

    /**
     * 配置第一次挂载时解析一次的懒加载显示来源, 解析完成前显示空物品.
     *
     * @param lazyProvider 懒加载显示提供器
     * @return 此构建器
     */
    public ItemBuilder setLazyItemProvider(@NotNull LazyItemProvider lazyProvider) {
        return this.setLazyItemProvider(ItemProvider.EMPTY, lazyProvider);
    }

    /**
     * 配置第一次挂载时解析一次的懒加载显示来源.
     * <p>解析出来的 Provider 由这件 Item 的全部显示挂载共用, 之后不再解析.
     *
     * @param placeholder 解析完成前的显示内容
     * @param lazyProvider 懒加载显示提供器
     * @return 此构建器
     */
    public ItemBuilder setLazyItemProvider(@NotNull ItemStack placeholder, @NotNull LazyItemProvider lazyProvider) {
        return this.setLazyItemProvider(
                ItemProvider.constant(Objects.requireNonNull(placeholder, "placeholder")),
                lazyProvider
        );
    }

    /**
     * 配置第一次挂载时解析一次的懒加载显示来源.
     * <p>解析出来的 Provider 由这件 Item 的全部显示挂载共用, 之后不再解析.
     *
     * @param placeholder 解析完成前的显示内容
     * @param lazyProvider 懒加载显示提供器
     * @return 此构建器
     */
    public ItemBuilder setLazyItemProvider(@NotNull ItemProvider placeholder, @NotNull LazyItemProvider lazyProvider) {
        this.setSource(new SourceSpec.LazySpec(
                Objects.requireNonNull(placeholder, "placeholder"),
                Objects.requireNonNull(lazyProvider, "lazyProvider")
        ));
        return this;
    }

    /**
     * 配置每次渲染都可能重算的异步渲染显示来源, 还没有完成结果时显示空物品.
     *
     * @param asyncProvider 异步渲染提供器
     * @return 此构建器
     */
    public ItemBuilder setAsyncItemProvider(@NotNull AsyncItemProvider asyncProvider) {
        return this.setAsyncItemProvider(ItemProvider.EMPTY, asyncProvider);
    }

    /**
     * 配置每次渲染都可能重算的异步渲染显示来源.
     * <p>渲染时优先给出最近一次完成的结果, 并提交新的一次重算, 完成后刷新这一个槽位.
     *
     * @param placeholder 还没有完成结果时的显示内容
     * @param asyncProvider 异步渲染提供器
     * @return 此构建器
     */
    public ItemBuilder setAsyncItemProvider(@NotNull ItemStack placeholder, @NotNull AsyncItemProvider asyncProvider) {
        this.setSource(new SourceSpec.AsyncSpec(
                ItemProvider.constant(Objects.requireNonNull(placeholder, "placeholder")),
                Objects.requireNonNull(asyncProvider, "asyncProvider")
        ));
        return this;
    }

    /**
     * 配置每次渲染都可能重算的异步渲染显示来源.
     * <p>渲染时优先给出最近一次完成的结果, 并提交新的一次重算, 完成后刷新这一个槽位.
     *
     * @param placeholder 还没有完成结果时的显示内容
     * @param asyncProvider 异步渲染提供器
     * @return 此构建器
     */
    public ItemBuilder setAsyncItemProvider(@NotNull ItemProvider placeholder, @NotNull AsyncItemProvider asyncProvider) {
        this.setSource(new SourceSpec.AsyncSpec(
                Objects.requireNonNull(placeholder, "placeholder"),
                Objects.requireNonNull(asyncProvider, "asyncProvider")
        ));
        return this;
    }

    /**
     * 在 Item 被显示时增加一个 Window 周期刷新来源.
     *
     * @param periodTicks 正数 tick 周期
     * @return 此构建器
     */
    public ItemBuilder updatePeriodically(int periodTicks) {
        this.explicitRefreshPlan = RefreshPlan.every(periodTicks);
        return this;
    }

    /**
     * 配置点击处理器成功完成后主动失效 Item.
     *
     * @return 此构建器
     */
    public ItemBuilder updateOnClick() {
        this.updateOnClick = true;
        return this;
    }

    /**
     * 添加点击前置处理器.
     * 添加顺序执行, 第一个返回 false 的守卫会拒绝点击.
     *
     * @param guard 点击前置处理器
     * @return 此构建器
     */
    public ItemBuilder addClickGuard(@NotNull ItemGuard<? super ItemClick> guard) {
        return this.addClickGuard(guard, (ignoredItem, ignoredClick) -> { });
    }

    /**
     * 添加点击前置处理器与拒绝回调.
     *
     * @param guard 点击前置处理器
     * @param onRejected 点击前置处理器返回 false 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addClickGuard(@NotNull ItemGuard<? super ItemClick> guard, @NotNull Consumer<? super ItemClick> onRejected) {
        Objects.requireNonNull(onRejected, "onRejected");
        return this.addClickGuard(guard, (ignoredItem, click) -> onRejected.accept(click));
    }

    /**
     * 添加点击前置处理器与拒绝回调.
     *
     * @param guard 点击前置处理器
     * @param onRejected 点击前置处理器返回 false 时执行的回调
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
     * 添加可以访问 Item 自身的点击处理器, 供处理逻辑同时读取物品和点击事件.
     *
     * @param clickHandler 同时接收物品和点击事件的处理器
     * @return 此构建器
     */
    public ItemBuilder addClickHandler(@NotNull BiConsumer<? super Item, ? super ItemClick> clickHandler) {
        this.clickHandler = this.clickHandler.andThen(clickHandler);
        return this;
    }

    /**
     * 添加拖拽前置处理器.
     *
     * @param guard 拖拽前置处理器
     * @return 此构建器
     */
    public ItemBuilder addDragGuard(@NotNull ItemGuard<? super ItemDragClick> guard) {
        return this.addDragGuard(guard, (ignoredItem, ignoredDrag) -> { });
    }

    /**
     * 添加拖拽前置处理器与拒绝回调.
     *
     * @param guard 拖拽前置处理器
     * @param onRejected 前置处理器返回 false 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addDragGuard(@NotNull ItemGuard<? super ItemDragClick> guard, @NotNull Consumer<? super ItemDragClick> onRejected) {
        Objects.requireNonNull(onRejected, "onRejected");
        return this.addDragGuard(guard, (ignoredItem, drag) -> onRejected.accept(drag));
    }

    /**
     * 添加拖拽前置处理器与拒绝回调.
     *
     * @param guard 拖拽前置处理器
     * @param onRejected 前置处理器返回 false 时执行的回调
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
     * 添加可以访问 Item 自身的拖拽处理器, 供处理逻辑同时读取物品和拖拽事件.
     *
     * @param dragHandler 同时接收物品和拖拽事件的处理器
     * @return 此构建器
     */
    public ItemBuilder addDragHandler(@NotNull BiConsumer<? super Item, ? super ItemDragClick> dragHandler) {
        this.dragHandler = this.dragHandler.andThen(dragHandler);
        return this;
    }

    /**
     * 添加 Bundle 选择前置处理器.
     *
     * @param guard Bundle 选择前置处理器
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectGuard(@NotNull ItemGuard<? super BundleSelectClick> guard) {
        return this.addBundleSelectGuard(guard, (ignoredItem, ignoredSelect) -> { });
    }

    /**
     * 添加 Bundle 选择前置处理器与拒绝回调.
     *
     * @param guard Bundle 选择前置处理器
     * @param onRejected 前置处理器返回 false 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectGuard(@NotNull ItemGuard<? super BundleSelectClick> guard, @NotNull Consumer<? super BundleSelectClick> onRejected) {
        Objects.requireNonNull(onRejected, "onRejected");
        return this.addBundleSelectGuard(guard, (ignoredItem, select) -> onRejected.accept(select));
    }

    /**
     * 添加 Bundle 选择前置处理器与拒绝回调.
     *
     * @param guard Bundle 选择前置处理器
     * @param onRejected 前置处理器返回 false 时执行的回调
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
     * 添加可以访问 Item 自身的 Bundle 选择处理器.
     *
     * @param selectHandler 同时接收物品和选择事件的处理器
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectHandler(@NotNull BiConsumer<? super Item, ? super BundleSelectClick> selectHandler) {
        this.bundleHandler = this.bundleHandler.andThen(selectHandler);
        return this;
    }

    /**
     * 添加在 Item 完整构建后执行的修改器. 修改器按添加顺序执行.
     * <p>修改器可以保存 Item 引用, 建立外部注册关系或调用 {@link ObservableItem#notifyWindows()}.
     * 如果某个修改器抛出异常, 后续修改器不会执行, 异常由 {@link #build()} 直接抛出.</p>
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
                this.explicitRefreshPlan,
                this.clickGuards,
                this.dragGuards,
                this.bundleSelectGuards,
                this.clickHandler,
                this.dragHandler,
                this.bundleHandler,
                this.updateOnClick
        );
        // 构建完成后按添加顺序执行修改器, 让调用方拿到完整的 Item
        this.modifier.accept(item);
        return item;
    }

    // 写入显示来源声明, 并保证只配置一次.
    private void setSource(SourceSpec source) {
        if (this.sourceConfigured)
            throw new IllegalStateException("display source has already been configured");
        this.source = source;
        this.sourceConfigured = true;
    }

    // Item 的显示来源, 决定每次渲染使用的提供器与挂载行为.
    sealed interface DisplaySource permits DisplaySource.FixedDisplaySource, DisplaySource.LazyDisplaySource, DisplaySource.AsyncDisplaySource {

        // 获取当前渲染使用的提供器.
        ItemProvider provider();

        // Item 挂载到槽位时的回调. 默认无操作.
        default void onAttached() {
        }



        // 固定不变的显示来源
        record FixedDisplaySource(@NotNull ItemProvider provider) implements DisplaySource {
            public FixedDisplaySource {
                Objects.requireNonNull(provider, "provider");
            }
        }

        // 第一次挂载时异步解析一次, 之后复用结果的懒加载显示来源.
        final class LazyDisplaySource implements DisplaySource {
            private final AtomicReference<LazyItemProvider> pendingProvider; // 挂起的提供器, 取出后置 null 保证只解析一次
            private final Runnable invalidator;                         // 解析完成后通知 Window 失效的回调

            private volatile ItemProvider currentProvider;              // 当前渲染使用的提供器, 初始为占位内容, 解析完成后替换
            private final ItemProvider renderingProvider = context -> this.currentProvider.provide(context); // 始终委托当前提供器的渲染入口

            /**
             * 创建懒加载显示来源, 解析完成前渲染占位内容.
             *
             * @param placeholder 解析完成前的占位提供器
             * @param lazyProvider 懒加载显示提供器
             * @param invalidator 解析完成后通知 Window 失效的回调
             */
            LazyDisplaySource(ItemProvider placeholder, LazyItemProvider lazyProvider, Runnable invalidator) {
                this.currentProvider = Objects.requireNonNull(placeholder, "placeholder");
                this.pendingProvider = new AtomicReference<>(Objects.requireNonNull(lazyProvider, "lazyProvider"));
                this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
            }

            @Override
            public ItemProvider provider() {
                return this.renderingProvider;
            }

            // 仅第一次挂载真正提交解析, 后续直接复用结果.
            @Override
            public void onAttached() {
                // 取出并清空挂起的提供器, 保证同一 Item 多次挂载也只执行一次解析
                LazyItemProvider lazyProvider = this.pendingProvider.getAndSet(null);
                if (lazyProvider == null) return;

                // 同步抛出同样视为解析失败, 与异步异常走同一通道
                CompletionStage<? extends ItemProvider> stage;
                try {
                    stage = Objects.requireNonNull(lazyProvider.resolve(), "lazyProvider result");
                } catch (Throwable throwable) {
                    SparrowUI.getInstance().handleException("Failed to resolve lazy item provider", throwable);
                    return;
                }

                stage.whenComplete((provider, throwable) -> {
                    // 加载失败时转发异常, 保留占位显示
                    if (throwable != null) {
                        SparrowUI.getInstance().handleException("Failed to resolve lazy item provider", ThrowableUtils.unwrapCompletion(throwable));
                        return;
                    }
                    // 解析结果为 null 也视为失败, 避免渲染时空指针
                    if (provider == null) {
                        SparrowUI.getInstance().handleException("Failed to resolve lazy item provider", new NullPointerException("resolved provider"));
                        return;
                    }

                    // 替换当前提供器并通知窗口重新渲染
                    this.currentProvider = provider;
                    try {
                        this.invalidator.run();
                    } catch (RuntimeException exception) {
                        // 失效回调失败不能影响已完成的解析结果
                        SparrowUI.getInstance().handleException("Failed to invalidate windows for lazy item", exception);
                    }
                });
            }
        }

        // 每次渲染都可能重算的异步渲染显示来源.
        final class AsyncDisplaySource implements DisplaySource {
            private final ItemProvider placeholder;         // 还没有完成结果时的显示内容
            private final AsyncItemProvider asyncProvider;  // 用户提供的异步渲染提供器
            private final ItemProvider renderingProvider = this::render; // 渲染入口, 按槽位取出各自的寄存状态

            AsyncDisplaySource(ItemProvider placeholder, AsyncItemProvider asyncProvider) {
                this.placeholder = Objects.requireNonNull(placeholder, "placeholder");
                this.asyncProvider = Objects.requireNonNull(asyncProvider, "asyncProvider");
            }

            @Override
            public ItemProvider provider() {
                return this.renderingProvider;
            }

            // 先决策再提交, 最后才读结果: 用户返回已完成的阶段时, 这一次渲染就能拿到真值, 不必先显示占位.
            private ItemStack render(RenderContext context) {
                SlotState state = context.rendererState(this, SlotState::new);
                if (state.phase == SlotState.Phase.FRESH) {
                    state.phase = SlotState.Phase.IDLE;
                } else if (state.phase == SlotState.Phase.IDLE) {
                    state.phase = SlotState.Phase.IN_FLIGHT;
                    this.submit(state, context);
                }

                ItemStack rendered = state.lastRendered;
                return rendered == null ? this.placeholder.provide(context) : rendered;
            }

            // 提交一次重算.
            private void submit(SlotState state, RenderContext context) {
                CompletionStage<? extends ItemStack> stage;
                try {
                    stage = Objects.requireNonNull(this.asyncProvider.provide(context), "asyncProvider result");
                } catch (Throwable throwable) {
                    state.fail(throwable);
                    return;
                }

                stage.whenComplete((item, throwable) -> {
                    if (throwable != null) {
                        state.fail(ThrowableUtils.unwrapCompletion(throwable));
                    } else if (item == null) {
                        // 完成 null 也视为失败, 要表达空槽位应当显式完成空物品
                        state.fail(new NullPointerException("computed item"));
                    } else {
                        state.complete(item, context);
                    }
                });
            }

            // 异步渲染显示来源寄存在单个 Window 槽位上的重算状态.
            private static final class SlotState {
                private enum Phase {
                    IDLE,       // 没有重算在运行, 下一次渲染提交一次
                    IN_FLIGHT,  // 重算在运行, 渲染直接返回当前结果
                    FRESH       // 重算刚完成, 下一次渲染只取结果不提交
                }

                private volatile Phase phase = Phase.IDLE;
                private volatile ItemStack lastRendered; // 最近一次异步完成的服务端渲染结果; null 表示还没有

                // 写结果 -> 放行 phase -> 标脏:
                private void complete(ItemStack item, RenderContext context) {
                    this.lastRendered = item.clone(); // 用户仍持有原物品, 复制一份归渲染层所有
                    this.phase = Phase.FRESH;
                    try {
                        // 只失效自己这一个槽位, 走 Item.notifyWindow 会让 Item 的各个挂载槽触发重算.
                        context.window().notifyUpdate(context.windowSlot());
                    } catch (Throwable throwable) {
                        SparrowUI.getInstance().handleException("Failed to invalidate window slot for asynchronous item", throwable);
                    }
                }

                // 失败保留当前结果, 不标脏也不自动重试, 等下一次失效或周期刷新再提交.
                private void fail(Throwable throwable) {
                    this.phase = Phase.IDLE;
                    SparrowUI.getInstance().handleException("Failed to compute asynchronous item", throwable);
                }
            }
        }
    }



    // 构建器阶段的显示来源声明, 每次 {@link #build()} 都创建一个独立的 {@link DisplaySource}.
    sealed interface SourceSpec permits SourceSpec.ProviderSpec, SourceSpec.LazySpec, SourceSpec.AsyncSpec {

        DisplaySource create(Runnable invalidator);

        // 固定或上下文来源声明
        record ProviderSpec(ItemProvider provider) implements SourceSpec {

            @Override
            public DisplaySource create(Runnable invalidator) {
                return new DisplaySource.FixedDisplaySource(this.provider);
            }
        }

        // 懒加载来源声明
        record LazySpec(ItemProvider placeholder, LazyItemProvider lazyProvider) implements SourceSpec {

            @Override
            public DisplaySource create(Runnable invalidator) {
                return new DisplaySource.LazyDisplaySource(this.placeholder, this.lazyProvider, invalidator);
            }
        }

        // 异步渲染来源声明
        record AsyncSpec(ItemProvider placeholder, AsyncItemProvider asyncProvider) implements SourceSpec {

            @Override
            public DisplaySource create(Runnable ignoredInvalidator) {
                return new DisplaySource.AsyncDisplaySource(this.placeholder, this.asyncProvider);
            }
        }
    }
}
