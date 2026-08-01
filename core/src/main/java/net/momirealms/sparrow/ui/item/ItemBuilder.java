package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.click.BundleSelectClick;
import net.momirealms.sparrow.ui.click.ItemClick;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class ItemBuilder {
    private SourceSpec source = new SourceSpec.ProviderSpec(ItemProvider.EMPTY); // 显示来源声明, 只能配置一次
    private boolean sourceConfigured; // 显示来源是否已完成配置

    private BiConsumer<Item, ItemClick> clickHandler = (ignoredItem, ignoredClick) -> { };      // 点击处理器链
    private BiConsumer<Item, BundleSelectClick> bundleHandler = (ignoredItem, ignoredSelect) -> { }; // Bundle 选择处理器链
    private Consumer<ObservableItem> modifier = ignoredItem -> { }; // 构建完成后执行的修改器链
    private RefreshPlan explicitRefreshPlan = RefreshPlan.none();   // 显式配置的周期刷新计划
    private long throttleIntervalMillis;        // <= 0 表示未启用节流
    private ThrottleHandler throttleHandler;    // null 表示未添加节流处理器
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
     * 配置按服务器当前 tick 轮换的显示来源.
     *
     * @param periodTicks 帧切换周期
     * @param firstFrame 第一帧
     * @param remainingFrames 其余帧
     * @return 此构建器
     */
    public ItemBuilder setCyclingItemProvider(
            int periodTicks,
            @NotNull ItemProvider firstFrame,
            @NotNull ItemProvider... remainingFrames
    ) {
        List<ItemProvider> frames = new ArrayList<>(remainingFrames.length + 1);
        frames.add(firstFrame);
        frames.addAll(Arrays.asList(remainingFrames));
        return this.setCyclingItemProvider(periodTicks, frames);
    }

    /**
     * 配置按服务器当前 tick 轮换的显示来源.
     *
     * @param periodTicks 帧切换周期
     * @param frames 非空帧列表
     * @return 此构建器
     */
    public ItemBuilder setCyclingItemProvider(int periodTicks, @NotNull List<? extends ItemProvider> frames) {
        return this.setCyclingItemProvider(periodTicks, frames, Bukkit::getCurrentTick);
    }

    /**
     * 配置按指定 tick 来源轮换的显示来源, 允许注入 tick 来源以便测试.
     *
     * @param periodTicks 帧切换周期
     * @param frames 非空帧列表
     * @param tickSource 当前 tick 来源
     * @return 此构建器
     * @throws IllegalArgumentException 周期不是正数或帧列表为空时抛出
     */
    ItemBuilder setCyclingItemProvider(int periodTicks, @NotNull List<? extends ItemProvider> frames, @NotNull LongSupplier tickSource) {
        if (periodTicks <= 0)
            throw new IllegalArgumentException("periodTicks must be positive");
        List<ItemProvider> copiedFrames = List.copyOf(frames);
        if (copiedFrames.isEmpty())
            throw new IllegalArgumentException("frames must not be empty");

        // 单帧轮播退化为固定来源, 避免无意义的周期刷新
        if (copiedFrames.size() == 1) {
            this.setSource(new SourceSpec.ProviderSpec(copiedFrames.getFirst()));
        } else {
            this.setSource(new SourceSpec.CyclingSpec(periodTicks, copiedFrames, tickSource));
        }
        return this;
    }

    /**
     * 使用 Paper 全局异步调度器执行可能阻塞的 Provider 解析函数.
     * 解析函数仍然只会在 Item 第一次挂载时提交.
     *
     * @param placeholder 加载完成前的显示内容
     * @param supplier Provider 解析函数
     * @return 此构建器
     */
    public ItemBuilder setAsyncItemProvider(@NotNull ItemProvider placeholder, @NotNull Supplier<? extends ItemProvider> supplier) {
        Supplier<? extends ItemProvider> checkedSupplier = Objects.requireNonNull(supplier, "supplier");
        // 把同步解析函数包装成提交到 Paper 全局异步调度器的 AsyncLoader
        return this.setAsyncItemProvider(placeholder, () -> {
            CompletableFuture<ItemProvider> future = new CompletableFuture<>();
            Bukkit.getAsyncScheduler().runNow(
                    SparrowUI.getInstance().getPlugin(),
                    ignoredTask -> {
                        try {
                            future.complete(checkedSupplier.get());
                        } catch (Throwable throwable) {
                            // 解析异常不能丢给调度器, 统一走 future 的异常通道
                            future.completeExceptionally(throwable);
                        }
                    }
            );
            return future;
        });
    }

    /**
     * 配置第一次挂载时启动, 在 Item 生命周期内只执行一次的异步显示来源.
     *
     * @param placeholder 加载完成前的显示内容
     * @param loader 由调用方选择执行器并创建异步结果的懒加载器
     * @return 此构建器
     */
    public ItemBuilder setAsyncItemProvider(@NotNull ItemProvider placeholder, @NotNull AsyncLoader loader) {
        this.setSource(new SourceSpec.AsyncSpec(
                Objects.requireNonNull(placeholder, "placeholder"),
                Objects.requireNonNull(loader, "loader")
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
     * 限制同一玩家点击此 Item 的频率. 重复调用会替换之前的间隔.
     *
     * <p>同一玩家对同一 Item 的点击共享计时, 无论点击的是哪个显示槽位;
     * 不同玩家互不影响. 首次点击立即执行, 限制期内被拦截的点击不会延长间隔.
     * 只节流点击, Bundle 选择不受影响.</p>
     *
     * @param interval 两次有效点击之间至少间隔的毫秒数
     * @return 此构建器
     * @throws IllegalArgumentException 间隔不是正数
     */
    public ItemBuilder setThrottleMills(long interval) {
        if (interval <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        this.throttleIntervalMillis = interval;
        return this;
    }

    /**
     * 添加一个节流处理器. 处理器按添加顺序执行.
     *
     * @param handler 节流处理器
     * @return 此构建器
     */
    public ItemBuilder addThrottleHandler(@NotNull ThrottleHandler handler) {
        this.throttleHandler = this.throttleHandler == null ? handler : this.throttleHandler.andThen(handler);
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
     * @throws IllegalStateException 添加了节流处理器但没有启用节流
     */
    public ObservableItem build() {
        // 节流处理器必须搭配节流间隔, 否则永远不会被触发
        if (this.throttleHandler != null && this.throttleIntervalMillis <= 0)
            throw new IllegalStateException("throttle handlers require throttle to be enabled");
        // 仅在启用节流时组装节流配置
        ConfiguredItem.ThrottleConfig throttleConfig = this.throttleIntervalMillis > 0
                ? new ConfiguredItem.ThrottleConfig(this.throttleIntervalMillis, this.throttleHandler)
                : null;
        ObservableItem item = new ConfiguredItem(
                this.source,
                this.explicitRefreshPlan,
                this.clickHandler,
                this.bundleHandler,
                this.updateOnClick,
                throttleConfig
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

    /**
     * 创建此 Item 唯一一次异步解析阶段.
     * 调用方负责调度实际工作, 此方法自身不得阻塞调用线程.
     * 启动异步解析并返回结果阶段. 实现必须自行选择执行器, 不得阻塞调用线程.
     */
    @FunctionalInterface
    public interface AsyncLoader {
        CompletionStage<? extends ItemProvider> load();
    }

    //  Item 的显示来源, 决定每次渲染使用的提供器以及自身需要的周期刷新计划.
    sealed interface DisplaySource permits DisplaySource.FixedDisplaySource, DisplaySource.CyclingDisplaySource, DisplaySource.AsyncOnceDisplaySource {

        // 获取当前渲染使用的提供器.
        ItemProvider provider();

        // 获取此来源自身要求的周期刷新计划. 默认永不到期.
        default RefreshPlan refreshPlan() {
            return RefreshPlan.none();
        }

        // Item 挂载到槽位时的回调. 默认无操作.
        default void onAttached() {
        }



        // 固定不变的显示来源
        record FixedDisplaySource(@NotNull ItemProvider provider) implements DisplaySource {
            public FixedDisplaySource {
                Objects.requireNonNull(provider, "provider");
            }
        }



        // 按 tick 周期轮换多帧的显示来源.
        final class CyclingDisplaySource implements DisplaySource {
            private final List<ItemProvider> frames;    // 轮换帧列表, 至少两帧
            private final LongSupplier tickSource;      // 当前 tick 来源
            private final int periodTicks; // 帧切换周期
            private final ItemProvider renderingProvider; // 按当前帧委托渲染的提供器

            CyclingDisplaySource(int periodTicks, List<ItemProvider> frames, LongSupplier tickSource) {
                if (periodTicks <= 0)
                    throw new IllegalArgumentException("periodTicks must be positive");
                this.frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
                if (this.frames.size() < 2)
                    throw new IllegalArgumentException("cycling display requires at least two frames");
                this.tickSource = Objects.requireNonNull(tickSource, "tickSource");
                this.periodTicks = periodTicks;
                // 渲染入口按当前 tick 实时选择帧, 无需额外状态
                this.renderingProvider = context -> this.frames.get(this.frameIndex()).provide(context);
            }

            @Override
            public ItemProvider provider() {
                return this.renderingProvider;
            }

            @Override
            public RefreshPlan refreshPlan() {
                return RefreshPlan.every(this.periodTicks);
            }

            // 计算当前 tick 对应的帧下标.
            private int frameIndex() {
                // floorDiv/floorMod 保证 tick 为负时帧序号仍落在合法下标内
                long frame = Math.floorDiv(this.tickSource.getAsLong(), this.periodTicks);
                return (int) Math.floorMod(frame, this.frames.size());
            }
        }



        // 第一次挂载时异步解析一次, 之后复用结果的显示来源.
        final class AsyncOnceDisplaySource implements DisplaySource {
            private final AtomicReference<AsyncLoader> pendingLoader;   // 挂起的加载器, 取出后置 null 保证只加载一次
            private final Runnable invalidator;                         // 加载完成后通知 Window 失效的回调

            private volatile ItemProvider currentProvider;              // 当前渲染使用的提供器, 初始为占位内容, 加载完成后替换
            private final ItemProvider renderingProvider = context -> this.currentProvider.provide(context); // 始终委托当前提供器的渲染入口

            /**
             * 创建异步一次性显示来源, 加载完成前渲染占位内容.
             *
             * @param placeholder 加载完成前的占位提供器
             * @param loader 异步加载器
             * @param invalidator 加载完成后通知 Window 失效的回调
             */
            AsyncOnceDisplaySource(ItemProvider placeholder, AsyncLoader loader, Runnable invalidator) {
                this.currentProvider = Objects.requireNonNull(placeholder, "placeholder");
                this.pendingLoader = new AtomicReference<>(Objects.requireNonNull(loader, "loader"));
                this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
            }

            @Override
            public ItemProvider provider() {
                return this.renderingProvider;
            }

            // 仅第一次挂载真正提交加载, 后续直接复用结果.
            @Override
            public void onAttached() {
                // 取出并清空挂起的加载器, 保证同一 Item 多次挂载也只执行一次加载
                AsyncLoader loader = this.pendingLoader.getAndSet(null);
                if (loader == null) return;

                // 同步抛出同样视为解析失败, 与异步异常走同一通道
                CompletionStage<? extends ItemProvider> stage;
                try {
                    stage = Objects.requireNonNull(loader.load(), "loader result");
                } catch (Throwable throwable) {
                    SparrowUI.getInstance().handleException("Failed to resolve asynchronous item provider", throwable);
                    return;
                }

                stage.whenComplete((provider, throwable) -> {
                    // 加载失败时转发异常, 保留占位显示
                    if (throwable != null) {
                        SparrowUI.getInstance().handleException("Failed to resolve asynchronous item provider", unwrap(throwable));
                        return;
                    }
                    // 解析结果为 null 也视为失败, 避免渲染时空指针
                    if (provider == null) {
                        SparrowUI.getInstance().handleException("Failed to resolve asynchronous item provider", new NullPointerException("resolved provider"));
                        return;
                    }

                    // 替换当前提供器并通知窗口重新渲染
                    this.currentProvider = provider;
                    try {
                        this.invalidator.run();
                    } catch (RuntimeException exception) {
                        // 失效回调失败不能影响已完成的解析结果
                        SparrowUI.getInstance().handleException("Failed to invalidate windows for asynchronous item", exception);
                    }
                });
            }

            // 解开 CompletionException 包装, 优先返回真实原因.
            private static Throwable unwrap(Throwable throwable) {
                return throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
            }
        }
    }



    // 构建器阶段的显示来源声明, 每次 {@link #build()} 都创建一个独立的 {@link DisplaySource}.
    sealed interface SourceSpec permits SourceSpec.ProviderSpec, SourceSpec.CyclingSpec, SourceSpec.AsyncSpec {

        /**
         * 为一个新 Item 创建显示来源.
         *
         * @param invalidator Item 主动失效时通知 Window 的回调
         * @return 此 Item 独占的显示来源
         */
        DisplaySource create(Runnable invalidator);



        // 固定或上下文来源声明
        record ProviderSpec(ItemProvider provider) implements SourceSpec {

            @Override
            public DisplaySource create(Runnable invalidator) {
                return new DisplaySource.FixedDisplaySource(this.provider);
            }
        }



        // 轮换来源声明
        record CyclingSpec(int periodTicks, List<ItemProvider> frames, LongSupplier tickSource) implements SourceSpec {

            @Override
            public DisplaySource create(Runnable invalidator) {
                return new DisplaySource.CyclingDisplaySource(this.periodTicks, this.frames, this.tickSource);
            }
        }



        // 异步一次性来源声明
        record AsyncSpec(ItemProvider placeholder, AsyncLoader loader) implements SourceSpec {

            @Override
            public DisplaySource create(Runnable invalidator) {
                return new DisplaySource.AsyncOnceDisplaySource(this.placeholder, this.loader, invalidator);
            }
        }
    }
}
