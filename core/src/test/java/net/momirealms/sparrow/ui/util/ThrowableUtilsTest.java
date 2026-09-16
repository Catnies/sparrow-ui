package net.momirealms.sparrow.ui.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThrowableUtilsTest {

    @Test
    void combinesFailuresUnderTheFirstThrowable() {
        Throwable first = new IllegalStateException("first");
        Throwable next = new IllegalArgumentException("next");

        assertSame(next, ThrowableUtils.combine(null, next));
        assertSame(first, ThrowableUtils.combine(first, next));
        assertSame(next, first.getSuppressed()[0]);
    }

    @Test
    void capturesUncheckedFailuresUnderTheFirstThrowable() {
        Throwable first = new IllegalStateException("first");
        RuntimeException next = new IllegalArgumentException("next");

        assertSame(first, ThrowableUtils.captureUnchecked(first, () -> {
            throw next;
        }));

        assertSame(next, first.getSuppressed()[0]);
        assertSame(next, ThrowableUtils.captureUnchecked(null, () -> {
            throw next;
        }));
    }

    @Test
    void rethrowsRuntimeExceptionWithoutWrapping() {
        RuntimeException expected = new IllegalStateException("failure");
        RuntimeException actual = assertThrows(
                RuntimeException.class,
                () -> ThrowableUtils.throwIfUnchecked(expected)
        );

        assertSame(expected, actual);
    }

    @Test
    void rethrowsErrorWithoutWrapping() {
        Error expected = new AssertionError("failure");
        Error actual = assertThrows(Error.class, () -> ThrowableUtils.throwIfUnchecked(expected));

        assertSame(expected, actual);
    }

    @Test
    void ignoresNullAndCheckedExceptions() {
        assertDoesNotThrow(() -> ThrowableUtils.throwIfUnchecked(null));
        assertDoesNotThrow(() -> ThrowableUtils.throwIfUnchecked(new Exception("checked")));
    }
}
