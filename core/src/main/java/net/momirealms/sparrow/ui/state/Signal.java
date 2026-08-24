package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 保存一个可拉取的值, 值可能变化时向订阅者发送失效通知.
 * <p>通知不携带值. 默认使用 {@link Objects#equals} 判等, 需要其他规则时使用带 {@code sameValue} 的工厂.
 * 原地修改可变对象再写回同一引用通常会被判为相同, 可变状态应通过新对象发布.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public sealed interface Signal<T> permits MutableSignal, AsyncSignal, AbstractSignal, ListSignal, SetSignal, MapSignal {

    /**
     * 读取当前值. 具体类型可能返回缓存结果、占位值或集合装饰器的活视图.
     *
     * @return 当前值
     */
    T get();

    /**
     * 订阅后续失效. 订阅时不补发当前状态, 回调需要值时自行调用 {@link #get()}.
     * <p>回调在触发失效的线程同步执行, 同一回调可能被多个写入线程并发调用.
     * <strong>回调必须线程安全, 且不得直接或间接使同一个 signal 再次失效</strong>. 实现会尽力拦截同线程重入并以 {@link IllegalStateException} 上报,
     * 其中已经完成的写入仍然生效, 同轮其他订阅者也会继续收到通知.
     * <p>回调抛出的 {@link RuntimeException} 交给 Sparrow 的异常处理器, 不会回流给写入方.
     * <p>signal 弱持有订阅节点. <strong>调用方必须保存返回的凭证</strong>, 凭证被回收后订阅会自动消亡.
     *
     * <pre>{@code
     * private final Subscription amountBinding =
     *         amount.onDirty(() -> render(amount.get()));
     * }</pre>
     *
     * @param listener 失效回调
     * @return 订阅凭证, 可用于提前退订
     */
    @NotNull
    Subscription onDirty(@NotNull Runnable listener);

    /**
     * 创建一个可写数据源.
     *
     * @param <T> 值类型
     * @param initial 初始值, 允许为 {@code null}
     * @return 可写 signal
     */
    @NotNull
    static <T> MutableSignal<T> of(T initial) {
        return new MutableSignalImpl<>(initial);
    }

    /**
     * 创建一个可写数据源, 并指定判等函数.
     * <p><strong>判等函数仅接收两个非 {@code null} 值</strong>.
     * 判等发生在写入线程, 函数必须廉价、无副作用且满足自反性. 不自反的函数会让相同引用重复发送失效.
     * <p><strong>signal 会在整个生命周期内持有判等函数, 禁止捕获 {@code Player}、{@code World}、{@code Window} 一类对象.</strong>
     *
     * <pre>{@code
     * MutableSignal<ItemStack> shown = Signal.of(stack, ItemStack::isSimilar);
     * }</pre>
     *
     * @param <T> 值类型
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
     * <p>并发拉取可能让 {@code mapper} 为同一个上游版本执行多次, 因此它必须是纯函数.
     *
     * @param <R> 派生值类型
     * @param mapper 纯函数, 可在任意线程被执行
     * @return 派生 signal
     */
    @NotNull
    <R> Signal<R> map(@NotNull Function<? super T, ? extends R> mapper);

    /**
     * 派生一个会截断重复值的 signal, 派生值变化时才向下游发送失效.
     * <p>判等用 {@link Objects#equals}, 换一种判断方式用 {@link #mapDistinct(Function, BiPredicate)}.
     * <p>有订阅者时, 上游每次失效都会立即执行 {@code mapper} 并判等. 没有订阅者时, 计算仍由下一次拉取触发.
     * 求值不持锁, 争用时可能为同一个上游版本执行多次, 因此 {@code mapper} 必须廉价且是纯函数.
     *
     * @param <R> 派生值类型
     * @param mapper 纯函数, 在失效线程与拉取线程被执行
     * @return 派生 signal
     */
    @NotNull
    <R> Signal<R> mapDistinct(@NotNull Function<? super T, ? extends R> mapper);

    /**
     * 使用给定判等函数的 {@link #mapDistinct(Function)}.
     * <p>判等函数跑在失效线程与拉取线程上, 与 {@code mapper} 受同一条约束, 必须廉价且是纯函数.
     *
     * @param <R> 派生值类型
     * @param mapper 纯函数, 在失效线程与拉取线程被执行
     * @param sameValue 判等函数, 语义见 {@link #of(Object, BiPredicate)}
     * @return 派生 signal
     */
    @NotNull
    <R> Signal<R> mapDistinct(@NotNull Function<? super T, ? extends R> mapper, @NotNull BiPredicate<? super R, ? super R> sameValue);

    /**
     * 防抖, 上游每失效一次就把通知推后 {@code ticks} 个 tick, 连续失效只在最后一次之后通知一次.
     * <p>有订阅期间 {@link #get()} 返回上一次通知时读取的值, 等待期间仍能读到旧值.
     * 没有订阅时退化为透传, 读到的就是上游当前值, 也不占调度任务.
     * <p>通知在全局区域调度线程上发出. 发出时会读一次上游, 上游若是惰性 {@link #map} 它的 mapper 就在这里跑,
     * <strong>读取必须廉价</strong>.
     *
     * <pre>{@code
     * MutableSignal<String> input = Signal.of("");
     * Signal<String> settled = input.debounce(6).mapDistinct(String::strip);
     * }</pre>
     *
     * @param ticks 静默多少 tick 之后通知, 必须为正
     * @return 防抖后的 signal
     * @throws IllegalArgumentException {@code ticks} 小于等于 0
     */
    @NotNull
    Signal<T> debounce(long ticks);

    /**
     * 按毫秒防抖, 任务挂在 Paper 异步调度器上, 其余值语义见 {@link #debounce(long)}.
     * <p>通知在异步线程上发出, 与 {@link AsyncSignal} 装载完成的线程同级, <strong>订阅者回调必须线程安全</strong>.
     *
     * @param millis 静默多少毫秒之后通知, 必须为正
     * @return 防抖后的 signal
     * @throws IllegalArgumentException {@code millis} 小于等于 0
     */
    @NotNull
    Signal<T> debounceMillis(long millis);

    /**
     * 节流, 两次通知之间至少隔 {@code ticks} 个 tick.
     * <p>距上次通知已满间隔时立即通知. 未满时记下待发, 到点再通知一次, 间隔内的多次失效合并成那一次.
     * 补发的那次也算一次通知, 下一个间隔从它开始数.
     * <p>有订阅时保持上一次通知的值, 无订阅时透传上游. 立即通知在上游失效线程发出, 补发在全局区域调度线程发出.
     *
     * @param ticks 两次通知之间至少隔多少 tick, 必须为正
     * @return 节流后的 signal
     * @throws IllegalArgumentException {@code ticks} 小于等于 0
     */
    @NotNull
    Signal<T> throttle(long ticks);

    /**
     * 按毫秒节流, 补发挂在 Paper 异步调度器上, 其余值语义见 {@link #throttle(long)}.
     * <p>补发在异步线程上发出, 与 {@link AsyncSignal} 装载完成的线程同级, <strong>订阅者回调必须线程安全</strong>.
     *
     * @param millis 两次通知之间至少隔多少毫秒, 必须为正
     * @return 节流后的 signal
     * @throws IllegalArgumentException {@code millis} 小于等于 0
     */
    @NotNull
    Signal<T> throttleMillis(long millis);

    /**
     * 创建一个异步数据源, {@link #get()} 立即返回占位值或最近完成的值, 重算由 {@code executor} 在后台执行.
     * <p>创建时即调度一次首载. 之后由 {@link AsyncSignal#dirty} 触发重载.
     * <p>装载完成后在当前执行线程发布新值并发送失效. 装载失败与执行器拒绝都交给 Sparrow 的异常处理器,
     * 读取仍返回上一份成功结果, 详见 {@link AsyncSignal#dirty}.
     *
     * @param <T> 值类型
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 在 executor 线程执行, 必须线程安全, 不得直接或间接使本 signal 失效
     * @return 异步 signal
     */
    @NotNull
    static <T> AsyncSignal<T> async(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader) {
        return async(placeholder, executor, loader, AbstractSignal.defaultSameValue());
    }

    /**
     * 创建一个使用自定义判等函数的异步数据源.
     * <p>判等函数在装载完成线程上执行, 判为相同的装载结果不产生失效.
     *
     * @param <T> 值类型
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 在 executor 线程执行, 必须线程安全, 不得直接或间接使本 signal 失效
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
     * 创建一个轮询的异步数据源, 有订阅期间每 {@code periodTicks} 个 tick 重新装载一次.
     * 没有订阅时与 {@link #async(Object, Executor, Supplier)} 一样, 只在 {@link AsyncSignal#dirty} 时装载.
     * <p>创建时即调度一次首载. 订阅到来时若上一次装载结束已超过一个周期, 立刻补装载一次. 首载还在飞时不叠加.
     * <p>同一周期的轮询共用一个 tick 源, 会在同一拍一起发起装载, 执行器是节流点.
     * 装载失败与执行器拒绝按 {@link #async(Object, Executor, Supplier)} 处理, 下一拍照常再试.
     *
     * <pre>{@code
     * AsyncSignal<Long> sampled = Signal.polling(0L, executor, counter::get, 100); // 每 100 tick 重载一次
     * }</pre>
     *
     * @param <T> 值类型
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 约束见 {@link #async(Object, Executor, Supplier)}
     * @param periodTicks 轮询周期, 必须为正
     * @return 轮询的异步 signal
     * @throws IllegalArgumentException {@code periodTicks} 小于等于 0
     */
    @NotNull
    static <T> AsyncSignal<T> polling(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader, long periodTicks) {
        return polling(placeholder, executor, loader, periodTicks, AbstractSignal.defaultSameValue());
    }

    /**
     * 创建一个使用自定义判等函数的 tick 轮询数据源, 相同装载结果不发送失效.
     *
     * @param <T> 值类型
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 约束见 {@link #async(Object, Executor, Supplier)}
     * @param periodTicks 轮询周期, 必须为正
     * @param sameValue 判等函数, 语义见 {@link #of(Object, BiPredicate)}
     * @return 轮询的异步 signal
     * @throws IllegalArgumentException {@code periodTicks} 小于等于 0
     */
    @NotNull
    static <T> AsyncSignal<T> polling(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader, long periodTicks, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        AsyncSignalImpl<T> signal = new AsyncSignalImpl<>(placeholder, executor, loader, sameValue, AsyncSignalImpl.Polling.everyTicks(periodTicks));
        signal.scheduleInitialLoad();
        return signal;
    }

    /**
     * 创建按毫秒轮询的异步数据源, 时钟挂在 Paper 异步调度器上.
     * <p>装载照旧在 {@code executor} 上跑, 失效通知照旧从装载完成的线程发出, 订阅者看不出时钟挂在哪里.
     *
     * @param <T> 值类型
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 约束见 {@link #async(Object, Executor, Supplier)}
     * @param periodMillis 轮询周期毫秒数, 不小于 50
     * @return 轮询的异步 signal
     * @throws IllegalArgumentException {@code periodMillis} 小于 50
     */
    @NotNull
    static <T> AsyncSignal<T> pollingMillis(T placeholder, @NotNull Executor executor, @NotNull Supplier<? extends T> loader, long periodMillis) {
        return pollingMillis(placeholder, executor, loader, periodMillis, AbstractSignal.defaultSameValue());
    }

    /**
     * 创建一个使用自定义判等函数的毫秒轮询数据源.
     *
     * @param <T> 值类型
     * @param placeholder 首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行重算的执行器
     * @param loader 重算函数, 约束见 {@link #async(Object, Executor, Supplier)}
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

}
