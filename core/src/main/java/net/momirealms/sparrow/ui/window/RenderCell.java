package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
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

@ApiStatus.Internal
public final class RenderCell implements AutoCloseable {
    private static final long ABANDONED = -1L;

    private final RenderContext context;
    private final Runnable invalidator;
    private final String failureMessage;

    private final AtomicBoolean recomputeRequested = new AtomicBoolean(true);
    private final AtomicBoolean resetRequested = new AtomicBoolean();   // 任意线程提交的作废请求, 由渲染消费
    private final AtomicLong inFlightToken = new AtomicLong(); // 0 = 空闲, ABANDONED = 已作废, 否则为在异步渲染任务的代数
    private volatile long generation = 1L;          // 当前 Provider 的代数
    private volatile @Nullable Completed lastCompleted; // 最近完成值
    private @Nullable Object activeSourceKey;       // 当前来源的身份

    public RenderCell(@NotNull RenderContext context, @NotNull Runnable invalidator, @NotNull String failureMessage) {
        this.context = Objects.requireNonNull(context, "context");
        this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
        this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
    }

    /**
     * 按渲染意图渲染, 仅允许所属消费方的实体调度调用.
     * <p>Direct 意图与同步 Adapter 直接返回立即内容并作废当前 Provider;
     * 异步 Provider 消费重算要求, 空闲时提交计算, 然后返回最近完成值或占位内容.
     *
     * @param intent 本轮渲染意图
     * @return 本次立即可显示的内容
     */
    @NotNull
    public ItemStack render(@NotNull Intent intent) {
        // 执行作废操作
        if (this.resetRequested.getAndSet(false)) {
            this.activeSourceKey = null;
            this.generation++;
            this.inFlightToken.set(ABANDONED);
        }
        // 执行渲染
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
                // 换了来源, 旧任务与旧值一并作废, 新来源重新起算.
                if (sourceKey != this.activeSourceKey) {
                    this.activeSourceKey = sourceKey;
                    this.generation++;
                    this.inFlightToken.set(ABANDONED);
                    this.recomputeRequested.set(true);
                }
                long generation = this.generation;
                // 计算期间到达的失效保留重算要求, 完成后再算一轮
                if (this.inFlightToken.get() != generation && this.recomputeRequested.getAndSet(false)) {
                    this.submit(provider, generation);
                }
                Completed completed = this.lastCompleted;
                if (completed != null && completed.generation == generation) {
                    yield completed.item;
                }
                // 占位提供器当场算出内容, 没有占位时用消费方的兜底内容.
                yield placeholder != null
                        ? Objects.requireNonNull(placeholder.provideImmediately(this.context), "placeholder item")
                        : lastResort;
            }
        };
    }

    private void submit(ItemProvider provider, long generation) {
        this.inFlightToken.set(generation);
        CompletableFuture<? extends ItemStack> future;
        try {
            future = Objects.requireNonNull(provider.provide(this.context), "provider result");
        } catch (Throwable throwable) {
            this.inFlightToken.compareAndSet(generation, 0L);
            SparrowUI.getInstance().handleException(this.failureMessage, throwable);
            return;
        }
        if (future.isDone()) {
            this.completeNow(generation, future);
            return;
        }
        // 经弱引用挂接完成回调: 用户长期持有 Future 也不会钉住整个 Window
        WeakReference<RenderCell> owner = new WeakReference<>(this);
        future.whenComplete((item, throwable) -> {
            RenderCell cell = owner.get();
            if (cell != null) {
                cell.completeLater(generation, item, throwable);
            }
        });
    }

    // 同步完成的计算当场取值, 首帧即出真值, 不需要完成通知.
    private void completeNow(long generation, CompletableFuture<? extends ItemStack> future) {
        try {
            ItemStack item = Objects.requireNonNull(future.join(), "computed item");
            this.lastCompleted = new Completed(generation, item);
        } catch (Throwable throwable) {
            SparrowUI.getInstance().handleException(this.failureMessage, ThrowableUtils.unwrapCompletion(throwable));
        } finally {
            this.inFlightToken.compareAndSet(generation, 0L);
        }
    }

    // 异步完成的写回, 在完成计算的线程上执行.
    private void completeLater(long generation, @Nullable ItemStack item, @Nullable Throwable throwable) {
        // 任务已被作废 (Provider 换了/重开/关闭/迟到)
        if (!this.inFlightToken.compareAndSet(generation, 0L)) {
            return;
        }
        if (throwable != null) {
            SparrowUI.getInstance().handleException(this.failureMessage, ThrowableUtils.unwrapCompletion(throwable));
            if (this.recomputeRequested.get()) {
                this.onDirty();
            }
            return;
        }
        if (item == null) {
            SparrowUI.getInstance().handleException(this.failureMessage, new NullPointerException("computed item"));
            if (this.recomputeRequested.get()) {
                this.onDirty();
            }
            return;
        }
        // 值先于通知: 消费方看到脏槽位重新渲染时必能读到新值
        this.lastCompleted = new Completed(generation, item);
        this.onDirty();
    }

    /**
     * 要求重算当前的路径.
     * 当前 Provider 的数据变了, 下一次渲染重新提交计算.
     */
    public void dirty() {
        this.recomputeRequested.set(true);
    }

    private void onDirty() {
        try {
            this.invalidator.run();
        } catch (RuntimeException | Error exception) {
            SparrowUI.getInstance().handleException(this.failureMessage + " invalidation", exception);
        }
    }

    // 作废在飞任务与归属的 Provider.
    private void clearSource() {
        if (this.activeSourceKey != null) {
            this.activeSourceKey = null;
            this.generation++;
            this.inFlightToken.set(ABANDONED);
        }
    }

    /**
     * 作废当前 Provider, 在飞任务与最近完成值, 下一次渲染重新起算.
     * <p>在飞任务当场作废, 来源身份与代数留到下一次 {@link #render(Intent)} 换掉.
     * 调用时正在渲染的那一帧仍按它装配时的内容显示, 作废在下一帧生效.
     */
    public void reset() {
        this.resetRequested.set(true);
        this.inFlightToken.set(ABANDONED);
        this.recomputeRequested.set(true);
    }

    /**
     * 作废本 RenderCell, 关闭后不得再调用 {@link #render(Intent)}.
     */
    @Override
    public void close() {
        this.activeSourceKey = null;
        this.generation++;
        this.inFlightToken.set(ABANDONED);
        this.lastCompleted = null;
    }

    /**
     * 渲染意图, 消费方装配的"本轮显示什么"声明.
     */
    public sealed interface Intent permits Intent.Projected, Intent.Direct {

        /**
         * 经投影渲染 Provider.
         * <p>{@code sourceKey} 与 {@code provider} 分开: 前者回答"还是不是同一个来源",
         * 后者只是本轮的调用入口. 消费方每轮新建 {@code provider} 不会被当成来源变化,
         * 因此像视觉映射那样每次求值都产出新实例的装配方式也不会反复重算.
         *
         * @param sourceKey 来源身份, 消费方必须为同一来源稳定地给出同一个对象; 换了对象即视为换了来源
         * @param provider 本轮使用的 Provider
         * @param placeholder 首次成功结果前使用的占位提供器, {@code null} 表示没有
         * @param lastResort 没有占位提供器时的兜底内容
         */
        record Projected(
                @NotNull Object sourceKey,
                @NotNull ItemProvider provider,
                @Nullable ImmediateItemProvider placeholder,
                @NotNull ItemStack lastResort
        ) implements Intent {
            public Projected {
                Objects.requireNonNull(sourceKey, "sourceKey");
                Objects.requireNonNull(provider, "provider");
                Objects.requireNonNull(lastResort, "lastResort");
            }

            /**
             * 以 Provider 自身作为来源身份创建 Projected.
             */
            public Projected(
                    @NotNull ItemProvider provider,
                    @Nullable ImmediateItemProvider placeholder,
                    @NotNull ItemStack lastResort
            ) {
                this(provider, provider, placeholder, lastResort);
            }
        }

        /**
         * 直接显示立即值.
         *
         * @param value 本轮直接显示的内容
         */
        record Direct(@NotNull ItemStack value) implements Intent {
            public Direct {
                Objects.requireNonNull(value, "value");
            }
        }
    }

    // 最近完成值与它的代数, 打包成一个引用一次性发布; 代数不匹配的值在读侧视为不存在.
    private record Completed(long generation, @NotNull ItemStack item) {
    }
}
