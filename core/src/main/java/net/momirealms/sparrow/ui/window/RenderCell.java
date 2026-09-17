package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

// 一个消费位置(窗口槽位, 光标, 商人交易位)上的异步渲染投影: 失效就是一次重算请求, 渲染本身只是纯读.
//
// 同一个位置同一时刻最多一份计算在飞; 在飞期间来的失效合并成完成之后的一轮补算.
@ApiStatus.Internal
public final class RenderCell implements AutoCloseable {
    private final RenderContext context;                                // 求值 provider 用的渲染上下文
    private final Runnable invalidator;                                 // 算出结果之后用来叫醒消费方的回调
    private final Consumer<? super Throwable> exceptionHandler;         // 渲染异常往哪儿报

    private final AtomicBoolean recomputeRequested = new AtomicBoolean(true); // 当前来源要不要重算
    private final AtomicBoolean resetRequested = new AtomicBoolean();         // 别的线程提交的作废请求, 下一轮渲染开始时兑现
    private final AtomicLong inFlightToken = new AtomicLong();        // 在飞的异步任务属于哪一代; 0 表示没有在飞
    private volatile long generation = 1L;                            // 当前来源的代数, 换来源或者作废都会加一
    private volatile @Nullable Completed lastCompleted;               // 最近一次算完的结果
    private @Nullable Object activeSourceKey;                         // 当前来源身份, 换它就等于换了 provider

    public RenderCell(@NotNull RenderContext context, @NotNull Runnable invalidator, @NotNull Consumer<? super Throwable> exceptionHandler) {
        this.context = Objects.requireNonNull(context, "context");
        this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    /**
     * 按渲染意图算出这一帧, 只有消费方的实体调度能调.
     * <p>Direct 意图和 ImmediateItemProvider 都是当场出内容, 顺手把当前 Provider 作废.
     * <p>异步 Provider 这边: 消费掉重算请求, 空闲就提交一次计算, 然后返回最近一次算完的结果或者占位内容.
     *
     * @param intent 本轮渲染意图
     * @return 本次立即可显示的内容
     */
    @NotNull
    public ItemStack render(@NotNull Intent intent) {
        this.applyPendingReset();
        return switch (intent) {
            case Intent.Direct(var value) -> {
                this.clearSource();
                yield value;
            }
            case Intent.Projected(var sourceKey, var provider, var placeholder, var lastResort) -> {
                if (provider instanceof ImmediateItemProvider immediate) {
                    this.clearSource();
                    yield Objects.requireNonNull(immediate.provideImmediately(this.context), "rendered item");
                }
                if (sourceKey != this.activeSourceKey) {
                    this.switchSource(sourceKey);
                }
                long generation = this.generation;
                // 计算在飞的时候来的失效把请求留着, 算完了再补一轮
                if (this.inFlightToken.get() != generation && this.recomputeRequested.getAndSet(false)) {
                    this.submit(provider, generation);
                }
                Completed completed = this.lastCompleted;
                if (completed != null && completed.generation == generation) {
                    yield completed.item;
                }
                yield this.renderPlaceholder(placeholder, lastResort);
            }
        };
    }

    // 把别的线程提交的作废兑现掉
    private void applyPendingReset() {
        if (this.resetRequested.get() && this.resetRequested.getAndSet(false)) {
            this.activeSourceKey = null;
            this.generation++;
            this.inFlightToken.set(0L);
        }
    }

    // 来源换了: 在飞的任务和旧值一起作废, 新来源从头算
    private void switchSource(@NotNull Object sourceKey) {
        this.activeSourceKey = sourceKey;
        this.generation++;
        this.inFlightToken.set(0L);
        this.recomputeRequested.set(true);
    }

    // 有占位就当场算; 没占位用消费方给的兜底; 兜底也没有就给空物品
    @NotNull
    private ItemStack renderPlaceholder(@Nullable ImmediateItemProvider placeholder, @Nullable ItemStack lastResort) {
        if (placeholder != null) {
            return Objects.requireNonNull(placeholder.provideImmediately(this.context), "placeholder item");
        }
        return lastResort != null ? lastResort : ItemUtils.EMPTY;
    }

    // 向 provider 要一次计算; 当场完成就就地取值, 没完成就挂一个只弱持有本对象的完成回调
    private void submit(ItemProvider provider, long generation) {
        this.inFlightToken.set(generation);
        CompletableFuture<ItemStack> future;
        try {
            future = Objects.requireNonNull(provider.provide(this.context), "provider result");
        } catch (Throwable throwable) {
            this.inFlightToken.compareAndSet(generation, 0L);
            this.exceptionHandler.accept(throwable);
            return;
        }
        if (future.isDone()) {
            this.completeNow(generation, future);
            return;
        }
        // 完成回调只弱持有 RenderCell, 一直没完成的 Future 也钉不住 Window
        WeakReference<RenderCell> owner = new WeakReference<>(this);
        future.whenComplete((item, throwable) -> {
            RenderCell cell = owner.get();
            if (cell != null) {
                cell.completeLater(generation, item, throwable);
            }
        });
    }

    // 当场就完成的计算直接取值, 首帧就是真值, 不必再发完成通知
    private void completeNow(long generation, CompletableFuture<ItemStack> future) {
        try {
            ItemStack item = Objects.requireNonNull(future.join(), "computed item");
            this.lastCompleted = new Completed(generation, item);
        } catch (Throwable throwable) {
            this.exceptionHandler.accept(ThrowableUtils.unwrapCompletion(throwable));
        } finally {
            this.inFlightToken.compareAndSet(generation, 0L);
        }
    }

    // 异步完成的写回, 跑在把计算完成的那个线程上
    private void completeLater(long generation, @Nullable ItemStack item, @Nullable Throwable throwable) {
        // Provider 换了, 窗重开或者关掉, 旧任务都在这里被认出来并丢掉
        if (!this.inFlightToken.compareAndSet(generation, 0L)) {
            return;
        }
        if (throwable != null) {
            this.exceptionHandler.accept(ThrowableUtils.unwrapCompletion(throwable));
            if (this.recomputeRequested.get()) {
                this.onDirty();
            }
            return;
        }
        if (item == null) {
            this.exceptionHandler.accept(new NullPointerException("computed item"));
            if (this.recomputeRequested.get()) {
                this.onDirty();
            }
            return;
        }
        // 先把结果发布出去再通知, 消费方重渲染时一定读得到新值
        this.lastCompleted = new Completed(generation, item);
        this.onDirty();
    }

    // 让下一次渲染重算当前的 Provider
    public void dirty() {
        this.recomputeRequested.set(true);
    }

    // 通知本身抛了也交给异常出口, 栈迹还能看出是在哪儿失败的
    private void onDirty() {
        try {
            this.invalidator.run();
        } catch (RuntimeException | Error exception) {
            this.exceptionHandler.accept(exception);
        }
    }

    // 把在飞的任务和当前挂着的 Provider 一起作废
    private void clearSource() {
        if (this.activeSourceKey != null) {
            this.activeSourceKey = null;
            this.generation++;
            this.inFlightToken.set(0L);
        }
    }

    /**
     * 作废当前 Provider, 在飞的任务和最近完成值, 下一次渲染从头算起.
     * <p>在飞任务当场作废; 来源身份和代数留到下一次 {@link #render(Intent)} 时再换.
     * 调用这一刻正在渲染的那一帧还按它装配时的内容显示, 作废要到下一帧才生效.
     */
    public void reset() {
        this.resetRequested.set(true);
        this.inFlightToken.set(0L);
        this.recomputeRequested.set(true);
    }

    // 作废这个 RenderCell; 关掉之后不许再调 render.
    @Override
    public void close() {
        this.activeSourceKey = null;
        this.generation++;
        this.inFlightToken.set(0L);
        this.lastCompleted = null;
    }

    // 这一轮渲染打算怎么出内容: 要么直接给一份现成的物品, 要么经 provider 算出来
    public sealed interface Intent permits Intent.Projected, Intent.Direct {

        /**
         * 经投影渲染 Provider.
         * <p>{@code sourceKey} 说明来源有没有变, {@code provider} 才是本轮真正的入口.
         * 消费方每轮新造一个 provider 不会被当成换了来源, 所以像视觉映射那样每次求值都产出新实例的装配法也不会一直重算.
         *
         * @param sourceKey 来源身份; 同一个来源必须稳定地给出同一个对象, 换了对象就是换了来源
         * @param provider 本轮使用的 Provider
         * @param placeholder 首次成功结果前用的占位提供器, {@code null} 表示没有
         * @param lastResort 没有占位提供器时的兜底内容, {@code null} 表示空物品
         */
        record Projected(
                @NotNull Object sourceKey,
                @NotNull ItemProvider provider,
                @Nullable ImmediateItemProvider placeholder,
                @Nullable ItemStack lastResort
        ) implements Intent {
            public Projected {
                Objects.requireNonNull(sourceKey, "sourceKey");
                Objects.requireNonNull(provider, "provider");
            }

            public Projected(
                    @NotNull ItemProvider provider,
                    @Nullable ImmediateItemProvider placeholder,
                    @Nullable ItemStack lastResort
            ) {
                this(provider, provider, placeholder, lastResort);
            }
        }

        record Direct(@NotNull ItemStack value) implements Intent {
            public Direct {
                Objects.requireNonNull(value, "value");
            }
        }
    }

    // 结果和代数一起发布; 读的人看到代数对不上就当没有
    private record Completed(long generation, @NotNull ItemStack item) {
    }
}
