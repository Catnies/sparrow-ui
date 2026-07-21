package net.momirealms.sparrow.ui.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class MiscUtils {
    private MiscUtils() {}

    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> narrowConsumer(Consumer<? super T> consumer) {
        return (Consumer<T>) consumer;
    }

    public static <T> List<T> append(List<T> values, T value) {
        ArrayList<T> copy = new ArrayList<>(values.size() + 1);
        copy.addAll(values);
        copy.add(value);
        return List.copyOf(copy);
    }

    public static <T> List<T> remove(List<T> values, T value) {
        ArrayList<T> copy = new ArrayList<>(values);
        copy.remove(value);
        return List.copyOf(copy);
    }

    public static <T> List<Consumer<T>> removeConsumer(List<Consumer<T>> values, Consumer<? super T> value) {
        return remove(values, narrowConsumer(value));
    }

    public static <T> List<Consumer<T>> copyConsumers(List<? extends Consumer<? super T>> consumers) {
        ArrayList<Consumer<T>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }
}
