package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.SparrowUI;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

final class ExceptionHandlerProbe implements AutoCloseable {

    private final List<String> messages = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final BiConsumer<? super String, ? super Throwable> previous;
    ExceptionHandlerProbe() {
        this.previous = currentHandler();
        SparrowUI.getInstance().setExceptionHandler((message, throwable) -> {
            this.messages.add(message);
            this.failures.add(throwable);
        });
    }

    List<Throwable> failures() {
        return this.failures;
    }

    List<String> messages() {
        return this.messages;
    }

    @Override
    public void close() {
        SparrowUI.getInstance().setExceptionHandler(this.previous);
    }

    @SuppressWarnings("unchecked")
    private static BiConsumer<? super String, ? super Throwable> currentHandler() {
        try {
            Field field = SparrowUI.class.getDeclaredField("exceptionHandler");
            field.setAccessible(true);
            return (BiConsumer<? super String, ? super Throwable>) field.get(SparrowUI.getInstance());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read the SparrowUI exception handler", exception);
        }
    }
}
