package net.momirealms.sparrow.ui.state.internal.derive;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MutableListSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.ExceptionHandlerProbe;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import net.momirealms.sparrow.ui.state.internal.time.TickingTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergingSignalIncrementalTest {
    @Test
    void appendRemoveAndReorderKeepTheRetainedSubscriptions() throws ReflectiveOperationException {
        MutableSignal<Integer> first = Signal.of(0);
        MutableSignal<Integer> second = Signal.of(0);
        MutableSignal<Integer> added = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(first, second));
        Signal<Long> merged = Signals.merging(members, member -> member);
        AtomicInteger invalidations = new AtomicInteger();
        try (Subscription ignored = merged.onDirty(invalidations::incrementAndGet)) {
            List<?> firstEntries = entries(first);
            List<?> secondEntries = entries(second);
            members.set(List.of(first, second, added));
            assertEquals(firstEntries, entries(first));
            assertEquals(secondEntries, entries(second));

            members.set(List.of(added, second, first));
            assertEquals(firstEntries, entries(first));
            assertEquals(secondEntries, entries(second));
            assertEquals(3, merged.get());

            members.set(List.of(second, first));
            assertEquals(firstEntries, entries(first));
            assertEquals(secondEntries, entries(second));
            assertEquals(0, SignalTestAccess.entryCount(added));
            added.set(1);
            assertEquals(3, invalidations.get());
            first.set(1);
            second.set(1);
            assertEquals(5, invalidations.get());
        }
        assertEquals(0, SignalTestAccess.entryCount(first));
        assertEquals(0, SignalTestAccess.entryCount(second));
    }

    @Test
    void duplicateOccurrencesAreReusedOnceAndStillNotifyOnce() throws ReflectiveOperationException {
        MutableSignal<Integer> first = Signal.of(0);
        MutableSignal<Integer> second = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(first, second, first));
        Signal<Long> merged = Signals.merging(members, member -> member);
        AtomicInteger invalidations = new AtomicInteger();
        try (Subscription ignored = merged.onDirty(invalidations::incrementAndGet)) {
            List<?> firstEntries = entries(first);
            members.set(List.of(second, first, first, second));
            assertEquals(firstEntries, entries(first));
            assertEquals(2, SignalTestAccess.entryCount(second));
            first.set(1);
            second.set(1);
            assertEquals(3, invalidations.get());

            members.set(List.of(first));
            assertEquals(1, SignalTestAccess.entryCount(first));
            assertEquals(0, SignalTestAccess.entryCount(second));
            first.set(2);
            second.set(2);
            assertEquals(5, invalidations.get());

            members.set(List.of());
            assertEquals(0, SignalTestAccess.entryCount(first));
            first.set(3);
            assertEquals(6, invalidations.get());
        }
    }

    @Test
    void partialAttachmentFailureKeepsOldSubscriptionsAndClosesNewOnes() throws ReflectiveOperationException {
        MutableSignal<Integer> kept = Signal.of(0);
        MutableSignal<Integer> removed = Signal.of(0);
        MutableSignal<Integer> added = Signal.of(0);
        AtomicBoolean explode = new AtomicBoolean(true);
        Signal<Integer> failing = Signal.of(0).mapDistinct(value -> {
            if (explode.get()) {
                throw new IllegalStateException("activation failed");
            }
            return value;
        });
        MutableSignal<List<Signal<Integer>>> members = Signal.of(List.of(kept, removed));
        Signal<Long> merged = Signals.merging(members, member -> member);
        AtomicInteger invalidations = new AtomicInteger();
        try (Subscription ignored = merged.onDirty(invalidations::incrementAndGet)) {
            List<?> keptEntries = entries(kept);
            List<?> removedEntries = entries(removed);
            try (ExceptionHandlerProbe errors = new ExceptionHandlerProbe()) {
                members.set(List.of(kept, added, failing));
                assertEquals(1, errors.failures().size());
            }
            assertEquals(keptEntries, entries(kept));
            assertEquals(removedEntries, entries(removed));
            assertEquals(0, SignalTestAccess.entryCount(added));
            assertEquals(0, SignalTestAccess.entryCount(failing));
            assertEquals(0, invalidations.get());

            explode.set(false);
            merged.get();
            assertEquals(keptEntries, entries(kept));
            assertEquals(0, SignalTestAccess.entryCount(removed));
            assertEquals(1, SignalTestAccess.entryCount(added));
            assertEquals(1, SignalTestAccess.entryCount(failing));
            added.set(1);
            assertEquals(1, invalidations.get());
        }
        assertEquals(0, SignalTestAccess.entryCount(kept));
        assertEquals(0, SignalTestAccess.entryCount(added));
        assertEquals(0, SignalTestAccess.entryCount(failing));
    }

    @Test
    void inactivePullsAndReactivationFollowTheCurrentMembers() {
        MutableSignal<Integer> first = Signal.of(0);
        MutableSignal<Integer> second = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(first));
        Signal<Long> merged = Signals.merging(members, member -> member);
        AtomicInteger invalidations = new AtomicInteger();
        Subscription initial = merged.onDirty(invalidations::incrementAndGet);
        long before = merged.get();
        initial.close();
        members.set(List.of(second, second));
        second.set(1);
        assertNotEquals(before, merged.get());
        assertEquals(0, SignalTestAccess.entryCount(first));
        assertEquals(0, SignalTestAccess.entryCount(second));
        assertEquals(0, invalidations.get());

        try (Subscription ignored = merged.onDirty(invalidations::incrementAndGet)) {
            assertEquals(2, SignalTestAccess.entryCount(second));
            second.set(2);
            first.set(1);
            assertEquals(1, invalidations.get());
        }
        assertEquals(0, SignalTestAccess.entryCount(second));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pullsSeeWritesBeforeTheirNotificationsArrive(boolean changeMembership) throws Exception {
        MutableSignal<Integer> first = Signal.of(0);
        MutableSignal<Integer> added = Signal.of(0);
        MutableSignal<List<MutableSignal<Integer>>> members = Signal.of(List.of(first));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Signal<?> blocked = changeMembership ? members : first;
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Subscription blocker = blocked.onDirty(() -> {
                 entered.countDown();
                 await(release);
             })) {
            Signal<Long> merged = Signals.merging(members, member -> member);
            AtomicInteger invalidations = new AtomicInteger();
            try (Subscription ignored = merged.onDirty(invalidations::incrementAndGet)) {
                long before = merged.get();
                var write = executor.submit(() -> {
                    if (changeMembership) {
                        members.set(List.of(first, added));
                    } else {
                        first.set(1);
                    }
                });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertFalse(write.isDone());
                    assertEquals(before + 1, merged.get());
                    assertEquals(before + 1, merged.get());
                    assertEquals(0, invalidations.get());
                } finally {
                    release.countDown();
                }
                write.get(5, TimeUnit.SECONDS);
                assertEquals(1, invalidations.get());
                assertEquals(before + 1, merged.get());
            }
        }
    }

    @Test
    void synchronousPollingRefreshDuringAttachmentKeepsRetainedMembers() throws InterruptedException {
        TickingTestSupport.install();
        try {
            MutableSignal<Integer> kept = Signal.of(0);
            AtomicInteger loads = new AtomicInteger();
            Signal<Integer> polling = Signal.polling(0, Runnable::run, loads::incrementAndGet, 1);
            Thread.sleep(60);
            MutableSignal<List<Signal<Integer>>> members = Signal.of(List.of(kept));
            Signal<Long> merged = Signals.merging(members, member -> member);
            AtomicInteger invalidations = new AtomicInteger();
            try (Subscription ignored = merged.onDirty(invalidations::incrementAndGet)) {
                members.set(List.of(polling, kept));
                assertEquals(2, loads.get());
                GcSupport.pressure();
                int before = invalidations.get();
                kept.set(1);
                assertEquals(before + 1, invalidations.get());
                assertEquals(1, SignalTestAccess.entryCount(kept));
                TickingTestSupport.advance(1);
                assertEquals(1, SignalTestAccess.entryCount(polling));
            }
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void equalCollectionContentsDoNotMergeDistinctSources() {
        MutableListSignal<Integer> first = ListSignal.of();
        MutableListSignal<Integer> equal = ListSignal.of();
        first.add(0);
        equal.add(0);
        MutableSignal<List<Signal<?>>> members = Signal.of(List.of(first), (left, right) -> false);
        Signal<Long> merged = Signals.merging(members, member -> member);
        AtomicInteger invalidations = new AtomicInteger();
        try (Subscription ignored = merged.onDirty(invalidations::incrementAndGet)) {
            members.set(List.of(equal));
            assertEquals(1, invalidations.get());
            assertEquals(0, SignalTestAccess.entryCount(first));
            assertEquals(1, SignalTestAccess.entryCount(equal));

            members.set(List.of(equal, first));
            assertEquals(1, SignalTestAccess.entryCount(first));
            assertEquals(1, SignalTestAccess.entryCount(equal));
            first.add(1);
            equal.add(2);
            assertEquals(4, invalidations.get());
        }
        assertEquals(0, SignalTestAccess.entryCount(first));
        assertEquals(0, SignalTestAccess.entryCount(equal));
    }

    private static List<?> entries(Signal<?> signal) throws ReflectiveOperationException {
        Field field = AbstractSignal.class.getDeclaredField("entries");
        field.setAccessible(true);
        return List.copyOf((List<?>) field.get(signal));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
