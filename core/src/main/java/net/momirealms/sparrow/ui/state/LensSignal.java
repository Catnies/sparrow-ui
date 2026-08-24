package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

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
