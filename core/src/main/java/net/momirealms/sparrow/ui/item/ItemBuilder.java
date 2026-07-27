package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.BundleSelect;
import net.momirealms.sparrow.ui.ItemClick;
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

/**
 * 通过一份声明式配置构建 Item.
 *
 * <p>显示来源只能配置一次；fixed/contextual、cycling 和 async-once 互斥.
 * Builder 始终构建内部 {@link ConfiguredItem}，简单静态 Item 由 {@link Item#simple(ItemProvider)} 创建.</p>
 */
public final class ItemBuilder {
    private SourceSpec source = new SourceSpec.ProviderSpec(ItemProvider.EMPTY);
    private boolean sourceConfigured;

    private BiConsumer<Item, ItemClick> clickHandler = (ignoredItem, ignoredClick) -> { };
    private BiConsumer<Item, BundleSelect> bundleHandler = (ignoredItem, ignoredSelect) -> { };
    private Consumer<ObservableItem> modifier = ignoredItem -> { };
    private RefreshPlan explicitRefreshPlan = RefreshPlan.none();
    private long throttleIntervalMillis; // <= 0 表示未启用节流
    private ThrottleHandler throttleHandler; // null 表示未添加节流处理器
    private boolean updateOnClick;
    private BiConsumer<? super String, ? super Throwable> asyncExceptionHandler = SparrowUI.getInstance()::handleException;

    /**
     * 配置固定或依赖 RenderContext 的 ItemProvider.
     *
     * @param itemProvider ItemProvider
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

    ItemBuilder setCyclingItemProvider(int periodTicks, @NotNull List<? extends ItemProvider> frames, @NotNull LongSupplier tickSource) {
        if (periodTicks <= 0)
            throw new IllegalArgumentException("periodTicks must be positive");
        List<ItemProvider> copiedFrames = List.copyOf(frames);
        if (copiedFrames.isEmpty())
            throw new IllegalArgumentException("frames must not be empty");

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
        return this.setAsyncItemProvider(placeholder, () -> {
            CompletableFuture<ItemProvider> future = new CompletableFuture<>();
            Bukkit.getAsyncScheduler().runNow(
                    SparrowUI.getInstance().getPlugin(),
                    ignoredTask -> {
                        try {
                            future.complete(checkedSupplier.get());
                        } catch (Throwable throwable) {
                            future.completeExceptionally(throwable);
                        }
                    }
            );
            return future;
        });
    }

    /**
     * 配置第一次挂载时启动、在 Item 生命周期内只执行一次的异步显示来源.
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
        return addClickHandler((ignoredItem, click) -> clickHandler.accept(click));
    }

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
    public ItemBuilder addBundleSelectHandler(@NotNull Consumer<? super BundleSelect> selectHandler) {
        return addBundleSelectHandler((ignoredItem, select) -> selectHandler.accept(select));
    }

    /**
     * 添加可以访问 Item 自身的 Bundle 选择处理器.
     *
     * @param selectHandler 选择处理器
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectHandler(@NotNull BiConsumer<? super Item, ? super BundleSelect> selectHandler) {
        this.bundleHandler = this.bundleHandler.andThen(selectHandler);
        return this;
    }

    /**
     * 添加在 Item 完整构建后执行的修改器. 修改器按添加顺序执行.
     *
     * <p>修改器可以保存 Item 引用、建立外部注册关系或调用 {@link ObservableItem#notifyWindows()}.
     * 如果某个修改器抛出异常，后续修改器不会执行，异常由 {@link #build()} 直接抛出.</p>
     *
     * @param modifier 构建完成后的修改器
     * @return 此构建器
     */
    public ItemBuilder addModifier(@NotNull Consumer<? super ObservableItem> modifier) {
        this.modifier = this.modifier.andThen(modifier);
        return this;
    }

    /**
     * 配置异步显示来源解析失败时的异常处理器.
     * 默认转发给 {@link SparrowUI#handleException(String, Throwable)}.
     *
     * @param handler 接收错误消息与异常的处理器
     * @return 此构建器
     */
    public ItemBuilder handleAsyncExceptionWith(@NotNull BiConsumer<? super String, ? super Throwable> handler) {
        this.asyncExceptionHandler = handler;
        return this;
    }

    /**
     * 构建具备主动通知能力的 Item，并依次执行所有修改器.
     *
     * @return 构建完成的 ObservableItem
     * @throws IllegalStateException 添加了节流处理器但没有启用节流
     */
    public ObservableItem build() {
        if (this.throttleHandler != null && this.throttleIntervalMillis <= 0) {
            throw new IllegalStateException("throttle handlers require throttle to be enabled");
        }

        ConfiguredItem.ThrottleConfig throttleConfig = this.throttleIntervalMillis > 0
                ? new ConfiguredItem.ThrottleConfig(this.throttleIntervalMillis, this.throttleHandler)
                : null;
        ObservableItem item = new ConfiguredItem(
                source.displayFactory(asyncExceptionHandler),
                explicitRefreshPlan,
                clickHandler,
                bundleHandler,
                updateOnClick,
                throttleConfig
        );
        this.modifier.accept(item);
        return item;
    }

    private void setSource(SourceSpec source) {
        if (sourceConfigured) {
            throw new IllegalStateException("display source has already been configured");
        }
        this.source = source;
        this.sourceConfigured = true;
    }

    /**
     * 创建此 Item 唯一一次异步解析阶段.
     * 调用方负责调度实际工作, 此方法自身不得阻塞调用线程.
     */
    @FunctionalInterface
    public interface AsyncLoader {
        CompletionStage<? extends ItemProvider> load();
    }

    @FunctionalInterface
    interface DisplayFactory {

        DisplaySource create(Runnable invalidator);

        static DisplayFactory fixed(@NotNull ItemProvider provider) {
            return ignoredItem -> new DisplaySource.FixedDisplaySource(provider);
        }

        static DisplayFactory cycling(int periodTicks, List<ItemProvider> frames, LongSupplier tickSource) {
            return ignoredItem -> new DisplaySource.CyclingDisplaySource(periodTicks, frames, tickSource);
        }

        static DisplayFactory asyncOnce(ItemProvider placeholder, AsyncLoader loader, BiConsumer<? super String, ? super Throwable> exceptionHandler) {
            return invalidator -> new DisplaySource.AsyncOnceDisplaySource(placeholder, loader, invalidator, exceptionHandler);
        }
    }

    sealed interface DisplaySource permits DisplaySource.FixedDisplaySource, DisplaySource.CyclingDisplaySource, DisplaySource.AsyncOnceDisplaySource {

        ItemProvider provider();

        default RefreshPlan refreshPlan() {
            return RefreshPlan.none();
        }

        default void onAttached() {
        }

        record FixedDisplaySource(@NotNull ItemProvider provider) implements DisplaySource {
            public FixedDisplaySource {
                Objects.requireNonNull(provider, "provider");
            }
        }

        final class CyclingDisplaySource implements DisplaySource {
            private final List<ItemProvider> frames;
            private final LongSupplier tickSource;
            private final int periodTicks;
            private final ItemProvider renderingProvider;

            CyclingDisplaySource(int periodTicks, List<ItemProvider> frames, LongSupplier tickSource) {
                if (periodTicks <= 0) {
                    throw new IllegalArgumentException("periodTicks must be positive");
                }
                this.frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
                if (this.frames.size() < 2) {
                    throw new IllegalArgumentException("cycling display requires at least two frames");
                }
                this.tickSource = Objects.requireNonNull(tickSource, "tickSource");
                this.periodTicks = periodTicks;
                this.renderingProvider = context -> this.frames.get(frameIndex()).provide(context);
            }

            @Override
            public ItemProvider provider() {
                return renderingProvider;
            }

            @Override
            public RefreshPlan refreshPlan() {
                return RefreshPlan.every(periodTicks);
            }

            private int frameIndex() {
                // floorDiv/floorMod 保证 tick 为负时帧序号仍落在合法下标内
                long frame = Math.floorDiv(tickSource.getAsLong(), periodTicks);
                return (int) Math.floorMod(frame, frames.size());
            }
        }

        final class AsyncOnceDisplaySource implements DisplaySource {
            private final AtomicReference<AsyncLoader> pendingLoader;
            private final Runnable invalidator;
            private final BiConsumer<? super String, ? super Throwable> exceptionHandler;

            private volatile ItemProvider currentProvider;
            private final ItemProvider renderingProvider = context -> currentProvider.provide(context);

            AsyncOnceDisplaySource(ItemProvider placeholder, AsyncLoader loader, Runnable invalidator, BiConsumer<? super String, ? super Throwable> exceptionHandler) {
                this.currentProvider = Objects.requireNonNull(placeholder, "placeholder");
                this.pendingLoader = new AtomicReference<>(Objects.requireNonNull(loader, "loader"));
                this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
                this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
            }

            @Override
            public ItemProvider provider() {
                return renderingProvider;
            }

            @Override
            public void onAttached() {
                // 取出并清空挂起的加载器, 保证同一 Item 多次挂载也只执行一次加载
                AsyncLoader loader = pendingLoader.getAndSet(null);
                if (loader == null) return;

                CompletionStage<? extends ItemProvider> stage;
                try {
                    stage = Objects.requireNonNull(loader.load(), "loader result");
                } catch (Throwable throwable) {
                    report("Failed to resolve asynchronous item provider", throwable);
                    return;
                }

                stage.whenComplete((provider, throwable) -> {
                    if (throwable != null) {
                        report("Failed to resolve asynchronous item provider", unwrap(throwable));
                        return;
                    }
                    if (provider == null) {
                        report(
                                "Failed to resolve asynchronous item provider",
                                new NullPointerException("resolved provider")
                        );
                        return;
                    }

                    currentProvider = provider;
                    try {
                        invalidator.run();
                    } catch (RuntimeException exception) {
                        report("Failed to invalidate windows for asynchronous item", exception);
                    }
                });
            }

            private void report(String message, Throwable throwable) {
                exceptionHandler.accept(message, throwable);
            }

            private static Throwable unwrap(Throwable throwable) {
                return throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
            }
        }
    }

    private sealed interface SourceSpec permits SourceSpec.ProviderSpec, SourceSpec.CyclingSpec, SourceSpec.AsyncSpec {

        DisplayFactory displayFactory(BiConsumer<? super String, ? super Throwable> exceptionHandler);

        record ProviderSpec(ItemProvider provider) implements SourceSpec {

            @Override
            public DisplayFactory displayFactory(BiConsumer<? super String, ? super Throwable> exceptionHandler) {
                return DisplayFactory.fixed(this.provider);
            }
        }

        record CyclingSpec(int periodTicks, List<ItemProvider> frames, LongSupplier tickSource) implements SourceSpec {

            @Override
            public DisplayFactory displayFactory(BiConsumer<? super String, ? super Throwable> exceptionHandler) {
                return DisplayFactory.cycling(this.periodTicks, this.frames, this.tickSource);
            }
        }

        record AsyncSpec(ItemProvider placeholder, AsyncLoader loader) implements SourceSpec {

            @Override
            public DisplayFactory displayFactory(BiConsumer<? super String, ? super Throwable> exceptionHandler) {
                return DisplayFactory.asyncOnce(this.placeholder, this.loader, exceptionHandler);
            }
        }
    }
}
