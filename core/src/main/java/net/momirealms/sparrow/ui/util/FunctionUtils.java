package net.momirealms.sparrow.ui.util;

import java.util.function.Consumer;

public final class FunctionUtils {
    private FunctionUtils() {}

    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> narrowConsumer(Consumer<? super T> consumer) {
        return (Consumer<T>) consumer;
    }
}
