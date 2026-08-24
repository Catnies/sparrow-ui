package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 可从任意线程写入的 {@link Signal}. 写入新值或基于当前值进行原子更新时, 判等相同的结果不会发送失效.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public sealed interface MutableSignal<T> extends Signal<T> permits MutableSignalImpl, MutablePartitionHandle, LensSignal {

    /**
     * 写入新值, 若与旧值相同则静默跳过, 不产生失效.
     *
     * @param value 新值, 允许为 {@code null}
     */
    void set(T value);

    /**
     * 基于当前值原子更新.
     * <p>发生写入争用时 {@code updater} 可能执行多次, <strong>必须无副作用并允许重试</strong>.
     *
     * @param updater 根据当前值计算新值的纯函数
     */
    void update(@NotNull UnaryOperator<T> updater);

    /**
     * 把当前值的一个字段单独拿出来, 当成一个可写 signal 用.
     * <p>字段值变化时 lens 才向下游失效. 写入 lens 会经本 signal 的 {@link #update} 重新构造宿主值,
     * 并发写入不同字段时仍使用同一条原子更新路径.
     *
     * <pre>{@code
     * record Settings(boolean sound, int volume) {
     *     Settings withSound(boolean sound) {
     *         return new Settings(sound, this.volume);
     *     }
     * }
     *
     * MutableSignal<Settings> settings = Signal.of(new Settings(true, 5));
     * MutableSignal<Boolean> sound = settings.lens(Settings::sound, Settings::withSound);
     * sound.set(false);
     * }</pre>
     *
     * <p>{@code getter} 会在失效线程和拉取线程执行, 争用时可能重跑. {@code getter} 与 {@code setter} 都必须是纯函数.
     * <p><strong>lens 会长期持有本 signal、getter 和 setter, 禁止在函数中捕获 {@code Player}、{@code World}、{@code Window} 一类对象.</strong>
     *
     * @param <F> 字段类型
     * @param getter 从宿主值取出字段
     * @param setter 用新的字段值造一个新的宿主值
     * @return 该字段的可写 signal
     */
    @NotNull
    default <F> MutableSignal<F> lens(@NotNull Function<? super T, ? extends F> getter, @NotNull BiFunction<? super T, ? super F, ? extends T> setter) {
        return this.lens(getter, setter, AbstractSignal.defaultSameValue());
    }

    /**
     * 创建一个使用给定判等函数的字段 lens.
     *
     * @param <F> 字段类型
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
