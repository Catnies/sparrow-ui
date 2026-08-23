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
public sealed interface Signal<T> permits MutableSignal, AsyncSignal, AbstractSignal {

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
        AsyncSignalImpl<T> signal = new AsyncSignalImpl<>(placeholder, executor, loader);
        signal.scheduleInitialLoad();
        return signal;
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
        AsyncSignalImpl<T> signal = new AsyncSignalImpl<>(placeholder, executor, loader, sameValue);
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
