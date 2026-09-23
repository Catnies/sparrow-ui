package net.momirealms.sparrow.ui.state.internal;

import net.momirealms.sparrow.ui.state.MutableSignal;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

@ApiStatus.Internal
public final class MutableSignalImpl<T> extends AbstractSignal<T> implements MutableSignal<T> {
    private final BiPredicate<? super T, ? super T> sameValue;
    private final AtomicReference<Versioned<T>> state;

    public MutableSignalImpl(T initial) {
        this(initial, defaultSameValue());
    }

    public MutableSignalImpl(T initial, BiPredicate<? super T, ? super T> sameValue) {
        this.sameValue = Objects.requireNonNull(sameValue, "sameValue");
        this.state = new AtomicReference<>(new Versioned<>(initial, 0L));
    }

    @Override
    public T get() {
        return this.state.get().value();
    }

    @Override
    public long version() {
        return this.state.get().version();
    }

    @Override
    public void set(T value) {
        while (true) {
            Versioned<T> current = this.state.get();
            if (same(this.sameValue, current.value(), value)) {
                return;
            }
            if (this.state.compareAndSet(current, new Versioned<>(value, current.version() + 1))) {
                this.notifyDirty();
                return;
            }
        }
    }

    @Override
    public void update(@NotNull UnaryOperator<T> updater) {
        Objects.requireNonNull(updater, "updater");
        while (true) {
            Versioned<T> current = this.state.get();
            T value = updater.apply(current.value());
            if (same(this.sameValue, current.value(), value)) {
                return;
            }
            if (this.state.compareAndSet(current, new Versioned<>(value, current.version() + 1))) {
                this.notifyDirty();
                return;
            }
        }
    }
}
