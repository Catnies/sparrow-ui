package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.SparrowUI;
import org.junit.jupiter.api.Test;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsyncSignalActivationRollbackRegressionTest {

    @Test
    void failedActivationClosesTheClockSubscription() {
        AbstractSignal<Long> clock = (AbstractSignal<Long>) Signal.of(0L);
        AtomicBoolean reject = new AtomicBoolean();
        Executor executor = command -> {
            if (reject.get()) throw new RejectedExecutionException("busy");
            command.run();
        };
        AsyncSignalImpl<Integer> signal = new AsyncSignalImpl<>(0, executor, () -> 1, Objects::equals, new AsyncSignalImpl.Polling(clock, 0L));
        signal.scheduleInitialLoad();
        reject.set(true);
        IllegalStateException handlerFailure = new IllegalStateException("handler failed");
        try (ExceptionHandlerProbe ignored = new ExceptionHandlerProbe()) {
            SparrowUI.getInstance().setExceptionHandler((message, failure) -> {
                throw handlerFailure;
            });

            assertSame(handlerFailure, assertThrows(IllegalStateException.class, () -> signal.onDirty(() -> {
            })));
        }

        assertEquals(0, signal.entryCount());
        assertEquals(0, clock.entryCount());
    }
}
