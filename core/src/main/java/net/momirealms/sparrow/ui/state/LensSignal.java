package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 宿主状态里某一个字段的可写视图.
 * <p>读侧就是宿主上的一个 mapDistinct 视图, 只在本字段真的变了时才向下游失效; 写落回宿主的 CAS 循环.
 *
 * @param <S> 宿主值类型
 * @param <F> 字段值类型
 */
final class LensSignal<S, F> extends MapDistinctSignal<S, F> implements MutableSignal<F> {
    private final MutableSignal<S> host;
    private final Function<? super S, ? extends F> getter;
    private final BiFunction<? super S, ? super F, ? extends S> setter;

    LensSignal(
            MutableSignal<S> host,
            Function<? super S, ? extends F> getter,
            BiFunction<? super S, ? super F, ? extends S> setter,
            BiPredicate<? super F, ? super F> sameValue
    ) {
        super(require(host), getter, sameValue);
        this.host = host;
        this.getter = getter;
        this.setter = setter;
    }

    @Override
    public void set(F value) {
        this.host.update(current -> this.setter.apply(current, value));
    }

    @Override
    public void update(@NotNull UnaryOperator<F> updater) {
        Objects.requireNonNull(updater, "updater");
        this.host.update(current -> {
            F field = this.getter.apply(current);
            return this.setter.apply(current, updater.apply(field));
        });
    }
}
