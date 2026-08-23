package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public sealed interface MutableSignal<T> extends Signal<T> permits MutableSignalImpl, MutablePartitionHandle, LensSignal {

    /**
     * 写入新值, 若与旧值相同则静默跳过, 不产生失效.
     *
     * @param value 新值, 允许为 {@code null}
     */
    void set(T value);

    /**
     * 基于当前值原子更新.
     *
     * @param updater 纯函数
     */
    void update(@NotNull UnaryOperator<T> updater);

    /**
     * 把当前值的一个字段单独拿出来, 当成一个可写 signal 用.
     * <p>读的一侧只在这个字段变了时才向下游失效, 写的一侧回到本 signal 的 {@link #update} 里跑.
     * <p>它存在的理由是让可复用的输入组件接上宿主状态. 组件只认识 "一个可写的 {@code F}", 不认识宿主的状态类型.
     *
     * <pre>{@code
     * record Settings(boolean sound, int volume) { ... }
     * MutableSignal<Settings> settings = Signal.of(new Settings(true, 5));
     * MutableSignal<Boolean> sound = settings.lens(Settings::sound, Settings::withSound);
     * toggle("开关音效", sound);
     * }</pre>
     *
     * <p>{@code getter} 跑在本 signal 的失效线程与拉取线程上, 争用时可能被重跑, 所以它必须是平凡的字段访问.
     * <p><strong>getter 与 setter 被返回的 lens 持有整个生命周期, 禁止捕获 {@code Player}、{@code World}、{@code Window} 一类对象.</strong>
     * <p>lens 强持有本 signal. 接在分区句柄上时这条链一直连到整个 {@link KeyedSignal}.
     *
     * @param getter 从宿主值取出字段
     * @param setter 用新的字段值造一个新的宿主值
     * @return 该字段的可写 signal
     */
    @NotNull
    default <F> MutableSignal<F> lens(@NotNull Function<? super T, ? extends F> getter, @NotNull BiFunction<? super T, ? super F, ? extends T> setter) {
        return this.lens(getter, setter, AbstractSignal.defaultSameValue());
    }

    /**
     * 同 {@link #lens(Function, BiFunction)}, 但用给定的判等函数比较字段值.
     *
     * @param getter 从宿主值取出字段
     * @param setter 用新的字段值造一个新的宿主值
     * @param sameValue 判等函数, 语义见 {@link Signal#of(Object, BiPredicate)}
     * @return 该字段的可写 signal
     */
    @NotNull
    default <F> MutableSignal<F> lens(@NotNull Function<? super T, ? extends F> getter, @NotNull BiFunction<? super T, ? super F, ? extends T> setter, @NotNull BiPredicate<? super F, ? super F> sameValue) {
        Objects.requireNonNull(getter, "getter");
        Objects.requireNonNull(setter, "setter");
        Objects.requireNonNull(sameValue, "sameValue");
        return new LensSignal<>(this, getter, setter, sameValue);
    }
}