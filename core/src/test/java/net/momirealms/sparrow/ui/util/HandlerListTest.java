package net.momirealms.sparrow.ui.util;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandlerListTest {

    @Test
    void addAppendsWithoutMutatingThePreviousSnapshot() {
        HandlerList<String> handlers = new HandlerList<>(List.of("first"));
        List<String> before = handlers.snapshot();
        handlers.append("second");

        assertEquals(List.of("first"), before);
        assertEquals(List.of("first", "second"), handlers.snapshot());
    }

    @Test
    void removeDropsOnlyTheFirstMatchingHandler() {
        HandlerList<String> handlers = new HandlerList<>(List.of("a", "b", "a"));
        handlers.remove("a");

        assertEquals(List.of("b", "a"), handlers.snapshot());
    }

    @Test
    void constructorAndSetPublishDefensiveImmutableSnapshots() {
        List<String> initial = new ArrayList<>(List.of("old"));
        HandlerList<String> handlers = new HandlerList<>(initial);
        List<String> before = handlers.snapshot();
        initial.add("leaked");
        List<String> replacement = new ArrayList<>(List.of("new", "list"));
        handlers.set(replacement);
        replacement.add("leaked");

        assertNotSame(initial, before);
        assertEquals(List.of("old"), before);
        assertThrows(UnsupportedOperationException.class, () -> before.add("mutated"));
        assertEquals(List.of("new", "list"), handlers.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> handlers.snapshot().add("mutated"));
    }

    @Test
    void forEachIsolatedRunsOnTheSnapshotTakenAtInvocation() {
        HandlerList<Runnable> handlers = new HandlerList<>(List.of());
        List<String> calls = new ArrayList<>();
        handlers.append(() -> handlers.append(() -> calls.add("late")));
        handlers.append(() -> calls.add("second"));
        handlers.forEachIsolated(Runnable::run, "failure", (message, throwable) -> { });

        assertEquals(List.of("second"), calls);
        assertEquals(3, handlers.snapshot().size());
    }

    @Test
    void forEachIsolatedReportsFailuresAndContinues() {
        AtomicInteger reports = new AtomicInteger();
        List<String> calls = new ArrayList<>();
        HandlerList<Runnable> handlers = new HandlerList<>(List.of(
                () -> calls.add("first"),
                () -> {
                    throw new IllegalStateException("injected handler failure");
                },
                () -> calls.add("third")
        ));
        handlers.forEachIsolated(Runnable::run, "failure", (message, throwable) -> reports.incrementAndGet());

        assertEquals(List.of("first", "third"), calls);
        assertEquals(1, reports.get());
    }
}
