package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class HandlerList<T> {
    private volatile List<T> handlers;

    public HandlerList(@NotNull List<? extends T> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    @NotNull
    @Unmodifiable
    public List<T> snapshot() {
        return this.handlers;
    }


    public void set(@NotNull List<? extends T> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public void append(@NotNull T handler) {
        ArrayList<T> copy = new ArrayList<>(this.handlers.size() + 1);
        copy.addAll(this.handlers);
        copy.add(handler);
        this.handlers = List.copyOf(copy);
    }

    public void remove(@NotNull T handler) {
        ArrayList<T> copy = new ArrayList<>(this.handlers);
        copy.remove(handler);
        this.handlers = List.copyOf(copy);
    }

    public void forEachIsolated(
            @NotNull Consumer<? super T> action,
            @NotNull String failureMessage,
            @NotNull BiConsumer<? super String, ? super Throwable> reporter
    ) {
        List<T> snapshot = this.handlers;
        for (int index = 0; index < snapshot.size(); index++) {
            try {
                action.accept(snapshot.get(index));
            } catch (Throwable throwable) {
                reporter.accept(failureMessage, throwable);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> narrowConsumer(Consumer<? super T> consumer) {
        return (Consumer<T>) consumer;
    }

    public static <T> List<Consumer<T>> copyConsumers(List<? extends Consumer<? super T>> consumers) {
        ArrayList<Consumer<T>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }

    @SuppressWarnings("unchecked")
    public static <T, U> BiConsumer<T, U> narrowBiConsumer(BiConsumer<? super T, ? super U> consumer) {
        return (BiConsumer<T, U>) consumer;
    }

    public static <T, U> List<BiConsumer<T, U>> copyBiConsumers(List<? extends BiConsumer<? super T, ? super U>> consumers) {
        ArrayList<BiConsumer<T, U>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowBiConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }
}
