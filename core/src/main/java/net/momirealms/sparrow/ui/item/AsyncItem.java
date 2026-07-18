package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.scheduler.SchedulerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 异步解析 ItemProvider 的 Item.
 *
 * <p>首个订阅者出现时发起一次解析. 解析一旦开始便不会因订阅者离开而取消或重新开始；
 * 成功解析的 Provider 会在此 Item 的整个生命周期内复用.</p>
 */
public final class AsyncItem extends AbstractStatefulItem {
    private static final BiConsumer<String, Throwable> DEFAULT_EXCEPTION_HANDLER = SparrowUI.getInstance()::handleException;

    private final Loader loader;
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler;
    private final BiConsumer<? super String, ? super Throwable> exceptionHandler;
    private final ItemProvider renderingProvider = context -> this.currentProvider.provide(context);
    private final AtomicBoolean started = new AtomicBoolean();

    private volatile ItemProvider currentProvider;

    /**
     * 创建由 CompletionStage 加载 Provider 的异步 Item.
     *
     * @param placeholder 解析完成前显示的 Provider
     * @param loader 此 Item 唯一一次请求的异步加载器
     */
    public AsyncItem(@NotNull ItemProvider placeholder, @NotNull Loader loader) {
        this(placeholder, loader, (_, _) -> { }, DEFAULT_EXCEPTION_HANDLER);
    }

    /**
     * 创建带点击处理器的异步 Item.
     *
     * @param placeholder 解析完成前显示的 Provider
     * @param loader 此 Item 唯一一次请求的异步加载器
     * @param clickHandler 点击处理器
     */
    public AsyncItem(
            @NotNull ItemProvider placeholder,
            @NotNull Loader loader,
            @NotNull BiConsumer<? super Item, ? super ItemClick> clickHandler
    ) {
        this(placeholder, loader, clickHandler, DEFAULT_EXCEPTION_HANDLER);
    }

    AsyncItem(
            @NotNull ItemProvider placeholder,
            @NotNull Loader loader,
            @NotNull BiConsumer<? super Item, ? super ItemClick> clickHandler,
            @NotNull BiConsumer<? super String, ? super Throwable> exceptionHandler
    ) {
        this.currentProvider = placeholder;
        this.loader = loader;
        this.clickHandler = clickHandler;
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * 使用 SparrowUI 的异步调度器执行可能阻塞的 Provider 解析函数.
     *
     * @param placeholder 解析完成前显示的 Provider
     * @param supplier Provider 解析函数
     * @return 异步 Item
     */
    public static AsyncItem supplyAsync(
            @NotNull ItemProvider placeholder,
            @NotNull Supplier<? extends ItemProvider> supplier
    ) {
        return supplyAsync(placeholder, supplier, SparrowUI.getInstance().scheduler());
    }

    /**
     * 使用给定 Sparrow 调度器执行可能阻塞的 Provider 解析函数.
     *
     * @param placeholder 解析完成前显示的 Provider
     * @param supplier Provider 解析函数
     * @param scheduler Sparrow 调度器
     * @return 异步 Item
     */
    public static AsyncItem supplyAsync(
            @NotNull ItemProvider placeholder,
            @NotNull Supplier<? extends ItemProvider> supplier,
            @NotNull SchedulerAdapter<?> scheduler
    ) {
        return new AsyncItem(
                placeholder,
                () -> {
                    CompletableFuture<ItemProvider> future = new CompletableFuture<>();
                    scheduler.executeAsync(() -> {
                        try {
                            future.complete(supplier.get());
                        } catch (Throwable throwable) {
                            future.completeExceptionally(throwable);
                        }
                    });
                    return future;
                }
        );
    }

    @Override
    public ItemProvider getItemProvider() {
        return renderingProvider;
    }

    /**
     * 注册观察者，并在第一次注册成功后启动此 Item 的异步解析.
     *
     * <p>取消订阅只解除观察关系，不会取消已经开始的解析.</p>
     *
     * @param observer 要通知的观察者
     * @return 用于取消此订阅的句柄
     */
    @Override
    public Subscription subscribe(@NotNull Observer<? super Item> observer) {
        Subscription subscription = super.subscribe(observer);
        try {
            startLoad();
            return subscription;
        } catch (RuntimeException | Error throwable) {
            subscription.close();
            throw throwable;
        }
    }

    @Override
    public void handleClick(ItemClick click) {
        clickHandler.accept(this, click);
    }

    private void startLoad() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        CompletionStage<? extends ItemProvider> stage;
        try {
            stage = Objects.requireNonNull(loader.load(), "loader result");
        } catch (Throwable throwable) {
            fail(throwable);
            return;
        }

        stage.whenComplete((provider, throwable) -> {
            if (throwable != null) {
                fail(unwrap(throwable));
                return;
            }
            if (provider == null) {
                fail(new NullPointerException("resolved provider"));
                return;
            }

            currentProvider = provider;
            notifySafely();
        });
    }

    private void fail(Throwable throwable) {
        exceptionHandler.accept("Failed to resolve asynchronous item provider", throwable);
    }

    private void notifySafely() {
        try {
            this.notifyWindows();
        } catch (RuntimeException exception) {
            exceptionHandler.accept("Failed to invalidate windows for asynchronous item", exception);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }

    /**
     * 创建此 Item 唯一一次异步解析阶段. 此方法自身不得阻塞调用线程.
     */
    @FunctionalInterface
    public interface Loader {
        CompletionStage<? extends ItemProvider> load();
    }
}
