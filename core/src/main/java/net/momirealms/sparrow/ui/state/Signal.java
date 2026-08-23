package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.util.TriFunction;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 响应式数据源: 持有单个值, 值过期时向订阅者广播失效.
 * <p>值按判等函数判断有没有变化, 默认是 {@link Objects#equals}, 原地改掉一个可变对象再写回同一个引用会被当成没变.
 * 想换一种判法就用带 {@code sameValue} 参数的工厂, 见 {@link #of(Object, BiPredicate)}.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public sealed interface Signal<T> permits MutableSignal, AsyncSignal, AbstractSignal, ListSignal, SetSignal, MapSignal {

    /**
     * 读取当前值.
     *
     * @return 当前快照值
     */
    T get();

    /**
     * 订阅失效信号, <strong>不允许在回调里让同一个 signal 再次失效</strong>, 这样做会抛出 {@link IllegalStateException}.
     * <p>通知不携带值, 也不触发求值. signal 弱持有监听器, <strong>订阅的存活由调用方持有的凭证决定</strong>,
     * 凭证不再被引用时订阅自动消亡并在后续派发时被剔除. 因此凭证必须存起来, 丢掉就等于退订.
     *
     * @param listener 失效监听器
     * @return 订阅凭证, <strong>必须持有</strong>, 丢弃即取消订阅
     */
    @NotNull
    Subscription onDirty(@NotNull Runnable listener);

    /**
     * 创建一个可写数据源.
     *
     * @param initial 初始值, 允许为 {@code null}
     * @return 可写 signal
     */
    @NotNull
    static <T> MutableSignal<T> of(T initial) {
        return new MutableSignalImpl<>(initial);
    }

    /**
     * 创建一个可写数据源, 并指定判等函数.
     * <p><strong>只有两个值都不是 {@code null} 时才会调用它</strong>.
     * <p>它必须廉价, 无副作用, 它在写入线程上执行, 抛出异常时的行为与 {@code equals} 抛出时一样.
     * <p><strong>它被 signal 持有整个生命周期, 禁止捕获 {@code Player}、{@code World}、{@code Window} 一类对象.</strong>
     *
     * <pre>{@code
     * MutableSignal<ItemStack> shown = Signal.of(stack, ItemStack::isSimilar);
     * }</pre>
     *
     * @param initial 初始值, 允许为 {@code null}
     * @param sameValue 判等函数
     * @return 可写 signal
     */
    @NotNull
    static <T> MutableSignal<T> of(T initial, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        return new MutableSignalImpl<>(initial, sameValue);
    }

    /**
     * 惰性派生, 失效原样透传, {@code mapper} 仅在派生值被拉取时执行, 并按上游版本缓存结果.
     *
     * @param mapper 纯函数, 可在任意线程被执行
     * @return 派生 signal
     */
    @NotNull
    <R> Signal<R> map(@NotNull Function<? super T, ? extends R> mapper);

    /**
     * 派生, 上游每次失效都会立即重算并与缓存值判等,
     * 判为相同则吞掉失效不再向下游传播, 适合逐层降频分派.
     * <p>判等用 {@link Objects#equals}, 换一种判断方式用 {@link #mapDistinct(Function, BiPredicate)}.
     * <p>本方法是整个模型里唯一在失效传播路径上求值的节点, 所以 {@code mapper} 要廉价. 求值不持锁,
     * 争用时它可能为同一个上游版本跑不止一次, 只有一份结果会被发布, 因此它必须是纯函数.
     *
     * @param mapper 纯函数, 在失效线程与拉取线程被执行
     * @return 派生 signal
     */
    @NotNull
    <R> Signal<R> mapDistinct(@NotNull Function<? super T, ? extends R> mapper);

    /**
     * 同 {@link #mapDistinct(Function)}, 但用给定的判等函数比较派生值.
     * <p>判等函数跑在失效线程与拉取线程上, 与 {@code mapper} 受同一条约束, 必须廉价且是纯函数.
     *
     * @param mapper 纯函数, 在失效线程与拉取线程被执行
     * @param sameValue 判等函数, 语义见 {@link #of(Object, BiPredicate)}
     * @return 派生 signal
     */
    @NotNull
    <R> Signal<R> mapDistinct(@NotNull Function<? super T, ? extends R> mapper, @NotNull BiPredicate<? super R, ? super R> sameValue);

    /**
     * 防抖, 上游每失效一次就把通知推后 {@code ticks} 个 tick, 连续失效只在最后一次之后通知一次.
     * <p>有订阅期间 {@link #get()} 返回上一次通知时从上游拍下的快照, 等待期间读到的还是旧值;
     * 没有订阅时退化为透传, 读到的就是上游当前值, 也不占调度任务.
     * <p>通知在全局区域调度线程上发出. 发出时会读一次上游, 上游若是惰性 {@link #map} 它的 mapper 就在这里跑,
     * <strong>读取必须廉价</strong>.
     *
     * <pre>{@code
     * MutableSignal<String> input = Signal.of("");
     * Signal<List<Entry>> results = input.debounce(6).mapDistinct(Catalog::search);
     * }</pre>
     *
     * @param ticks 静默多少 tick 之后通知, 必须为正
     * @return 防抖后的 signal
     * @throws IllegalArgumentException {@code ticks} 不是正数
     */
    @NotNull
    Signal<T> debounce(long ticks);

    /**
     * 同 {@link #debounce(long)}, 但以毫秒计, 任务挂在 Paper 异步调度器上.
     * <p>通知在异步线程上发出, 与 {@link AsyncSignal} 装载完成的线程同级, <strong>订阅者回调必须线程安全</strong>.
     *
     * @param millis 静默多少毫秒之后通知, 必须为正
     * @return 防抖后的 signal
     * @throws IllegalArgumentException {@code millis} 不是正数
     */
    @NotNull
    Signal<T> debounceMillis(long millis);

    /**
     * 节流, 两次通知之间至少隔 {@code ticks} 个 tick.
     * <p>距上次通知已满间隔时这次失效立即通知; 未满时记下待发, 到点再通知一次, 间隔内的多次失效合并成那一次.
     * 补发的那次也算一次通知, 下一个间隔从它开始数.
     * <p>值语义与无订阅时的行为同 {@link #debounce(long)}. 立即通知在让上游失效的线程上发出, 补发在全局区域调度线程上发出.
     *
     * @param ticks 两次通知之间至少隔多少 tick, 必须为正
     * @return 节流后的 signal
     * @throws IllegalArgumentException {@code ticks} 不是正数
     */
    @NotNull
    Signal<T> throttle(long ticks);

    /**
     * 同 {@link #throttle(long)}, 但以毫秒计, 补发挂在 Paper 异步调度器上.
     * <p>补发在异步线程上发出, 与 {@link AsyncSignal} 装载完成的线程同级, <strong>订阅者回调必须线程安全</strong>.
     *
     * @param millis 两次通知之间至少隔多少毫秒, 必须为正
     * @return 节流后的 signal
     * @throws IllegalArgumentException {@code millis} 不是正数
     */
    @NotNull
    Signal<T> throttleMillis(long millis);

    /**
     * 创建一个异步数据源, {@link #get()} 立即返回占位值或最近完成的值, 重算由 {@code executor} 在后台执行.
     * <p>创建时即调度一次首载. 之后由 {@link AsyncSignal#dirty} 触发重载.
     * <p>装载失败与执行器拒绝任务都交给统一异常处理器, 不会抛给调用方, 也不会让读取失败, 详见 {@link AsyncSignal#dirty}.
     *
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 在 executor 线程执行, 必须线程安全; 不得(直接或间接)使本 signal 失效, 这样做会抛出 {@link IllegalStateException}
     * @return 异步 signal
     */
    @NotNull
    static <T> AsyncSignal<T> async(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader) {
        return async(placeholder, executor, loader, AbstractSignal.defaultSameValue());
    }

    /**
     * 创建一个异步数据源, 并指定判等函数, 语义同 {@link #async(Object, Executor, Supplier)}.
     * <p>判等函数在装载完成线程上执行, 判为相同的装载结果不产生失效.
     *
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 在 executor 线程执行, 必须线程安全; 不得(直接或间接)使本 signal 失效, 这样做会抛出 {@link IllegalStateException}.
     * @param sameValue 判等函数, 语义见 {@link #of(Object, BiPredicate)}
     * @return 异步 signal
     */
    @NotNull
    static <T> AsyncSignal<T> async(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        AsyncSignalImpl<T> signal = new AsyncSignalImpl<>(placeholder, executor, loader, sameValue, null);
        signal.scheduleInitialLoad();
        return signal;
    }

    /**
     * 创建一个轮询的异步数据源, 有订阅期间每 {@code periodTicks} 个 tick 重新装载一次;
     * 没有订阅时与 {@link #async(Object, Executor, Supplier)} 一样, 只在 {@link AsyncSignal#dirty} 时装载.
     * <p>创建时即调度一次首载. 订阅到来时若上一次装载结束已超过一个周期, 立刻补装载一次; 首载还在飞时不叠加.
     * <p>同一周期的轮询共用一个 tick 源, 会在同一拍一起发起装载, 执行器是节流点.
     * 装载失败与执行器拒绝任务的处理同 {@link #async(Object, Executor, Supplier)}, 下一拍照常再试.
     *
     * <pre>{@code
     * AsyncSignal<List<Offer>> offers = Signal.polling(List.of(), executor, dao::topOffers, 100);   // 每 5 秒刷一次
     * }</pre>
     *
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 约束同 {@link #async(Object, Executor, Supplier)}
     * @param periodTicks 轮询周期, 必须为正
     * @return 轮询的异步 signal
     * @throws IllegalArgumentException {@code periodTicks} 不是正数
     */
    @NotNull
    static <T> AsyncSignal<T> polling(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader, long periodTicks) {
        return polling(placeholder, executor, loader, periodTicks, AbstractSignal.defaultSameValue());
    }

    /**
     * 同 {@link #polling(Object, Executor, Supplier, long)}, 但指定判等函数, 判为相同的装载结果不产生失效.
     *
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 约束同 {@link #async(Object, Executor, Supplier)}
     * @param periodTicks 轮询周期, 必须为正
     * @param sameValue 判等函数, 语义见 {@link #of(Object, BiPredicate)}
     * @return 轮询的异步 signal
     * @throws IllegalArgumentException {@code periodTicks} 不是正数
     */
    @NotNull
    static <T> AsyncSignal<T> polling(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader, long periodTicks, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        AsyncSignalImpl<T> signal = new AsyncSignalImpl<>(placeholder, executor, loader, sameValue, AsyncSignalImpl.Polling.everyTicks(periodTicks));
        signal.scheduleInitialLoad();
        return signal;
    }

    /**
     * 同 {@link #polling(Object, Executor, Supplier, long)}, 但以毫秒计, 时钟挂在 Paper 异步调度器上.
     * <p>装载照旧在 {@code executor} 上跑, 失效通知照旧从装载完成的线程发出, 订阅者看不出时钟挂在哪里.
     *
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 约束同 {@link #async(Object, Executor, Supplier)}
     * @param periodMillis 轮询周期毫秒数, 不小于 50
     * @return 轮询的异步 signal
     * @throws IllegalArgumentException {@code periodMillis} 小于 50
     */
    @NotNull
    static <T> AsyncSignal<T> pollingMillis(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader, long periodMillis) {
        return pollingMillis(placeholder, executor, loader, periodMillis, AbstractSignal.defaultSameValue());
    }

    /**
     * 同 {@link #pollingMillis(Object, Executor, Supplier, long)}, 但指定判等函数.
     *
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 约束同 {@link #async(Object, Executor, Supplier)}
     * @param periodMillis 轮询周期毫秒数, 不小于 50
     * @param sameValue 判等函数, 语义见 {@link #of(Object, BiPredicate)}
     * @return 轮询的异步 signal
     * @throws IllegalArgumentException {@code periodMillis} 小于 50
     */
    @NotNull
    static <T> AsyncSignal<T> pollingMillis(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader, long periodMillis, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        AsyncSignalImpl<T> signal = new AsyncSignalImpl<>(placeholder, executor, loader, sameValue, AsyncSignalImpl.Polling.everyMillis(periodMillis));
        signal.scheduleInitialLoad();
        return signal;
    }

    /**
     * 组合来源, 任一来源失效即失效, 值在拉取时以两个来源的快照重算.
     *
     * @param combiner 纯函数, 可在任意线程被执行
     * @return 组合 signal
     */
    @NotNull
    static <A, B, R> Signal<R> combine(@NotNull Signal<A> a, @NotNull Signal<B> b, @NotNull BiFunction<? super A, ? super B, ? extends R> combiner) {
        Objects.requireNonNull(combiner, "combiner");
        return new CombinedSignal<>(new AbstractSignal<?>[]{AbstractSignal.require(a), AbstractSignal.require(b)}, values -> {
            @SuppressWarnings("unchecked") R result = combiner.apply((A) values[0], (B) values[1]);
            return result;
        });
    }

    @NotNull
    static <A, B, C, R> Signal<R> combine(@NotNull Signal<A> a, @NotNull Signal<B> b, @NotNull Signal<C> c, @NotNull TriFunction<? super A, ? super B, ? super C, ? extends R> combiner) {
        Objects.requireNonNull(combiner, "combiner");
        return new CombinedSignal<>(new AbstractSignal<?>[]{AbstractSignal.require(a), AbstractSignal.require(b), AbstractSignal.require(c)}, values -> {
            @SuppressWarnings("unchecked") R result = combiner.apply((A) values[0], (B) values[1], (C) values[2]);
            return result;
        });
    }
}
