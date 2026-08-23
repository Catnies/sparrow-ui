package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 宿主状态里某一个字段的可写视图, 读走字段级截断, 写落回宿主的 CAS 循环.
 *
 * @param <S> 宿主值类型
 * @param <F> 字段值类型
 */
final class LensSignal<S, F> extends AbstractSignal<F> implements MutableSignal<F> {
    private final MutableSignal<S> source;
    private final Function<? super S, ? extends F> getter;
    private final BiFunction<? super S, ? super F, ? extends S> setter;
    private final AbstractSignal<F> view;   // 只对本字段失效的读侧
    private Subscription upstream;

    LensSignal(
            MutableSignal<S> source,
            Function<? super S, ? extends F> getter,
            BiFunction<? super S, ? super F, ? extends S> setter,
            BiPredicate<? super F, ? super F> sameValue
    ) {
        this.source = source;
        this.getter = getter;
        this.setter = setter;
        this.view = require(source.mapDistinct(getter, sameValue));
    }

    @Override
    public F get() {
        return this.view.get();
    }

    // 视图只在本字段真的变了时推进版本, 单一固定上游, 原样透传.
    @Override
    long version() {
        return this.view.version();
    }

    @Override
    public void set(F value) {
        this.source.update(current -> this.setter.apply(current, value));
    }

    @Override
    public void update(@NotNull UnaryOperator<F> updater) {
        Objects.requireNonNull(updater, "updater");
        this.source.update(current -> {
            F field = this.getter.apply(current);
            return this.setter.apply(current, updater.apply(field));
        });
    }

    @Override
    protected void onActive() {
        this.upstream = this.view.onDirty(this::notifyDirty);
    }

    @Override
    protected void onInactive() {
        this.upstream.close();
        this.upstream = null;
    }
}