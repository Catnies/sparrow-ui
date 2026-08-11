package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

final class MutableSignalImpl<T> extends AbstractSignal<T> implements MutableSignal<T> {
    private final AtomicReference<Versioned<T>> state;

    MutableSignalImpl(T initial) {
        this.state = new AtomicReference<>(new Versioned<>(initial, 0L));
    }

    @Override
    public T get() {
        return this.state.get().value();
    }

    @Override
    long version() {
        return this.state.get().version();
    }

    @Override
    public void set(T value) {
        while (true) {
            Versioned<T> current = this.state.get();
            if (Objects.equals(current.value(), value)) {
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
            if (Objects.equals(current.value(), value)) {
                return;
            }
            if (this.state.compareAndSet(current, new Versioned<>(value, current.version() + 1))) {
                this.notifyDirty();
                return;
            }
        }
    }
}
