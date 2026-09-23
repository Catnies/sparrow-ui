package net.momirealms.sparrow.ui.state.internal;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalTest {

    private final Bindings bindings = new Bindings();

    @Test
    void setUpdatesValueAndNotifiesSubscribers() {
        MutableSignal<String> signal = Signal.of("spring");
        List<String> received = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        signal.set("summer");

        assertEquals("summer", signal.get());
        assertEquals(List.of("summer"), received);
    }

    @Test
    void subscribeDoesNotReplayCurrentValue() {
        MutableSignal<Integer> signal = Signal.of(7);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));

        assertEquals(List.of(), received);
    }

    @Test
    void setEqualValueProducesNoInvalidation() {
        MutableSignal<String> signal = Signal.of("same");
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        signal.set("same");

        assertEquals(0, invalidations.get());
    }

    @Test
    void updateAppliesOperatorAndSkipsEqualResult() {
        MutableSignal<Integer> signal = Signal.of(10);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        signal.update(value -> value + 5);
        signal.update(value -> value);

        assertEquals(15, signal.get());
        assertEquals(1, invalidations.get());
    }

    @Test
    void nullValuesAreSupported() {
        MutableSignal<String> signal = Signal.of(null);

        assertNull(signal.get());
        signal.set("value");

        assertEquals("value", signal.get());
        signal.set(null);

        assertNull(signal.get());
    }

    @Test
    void onInvalidateDoesNotEvaluateDerivedChain() {
        MutableSignal<Integer> source = Signal.of(1);
        AtomicInteger mapperCalls = new AtomicInteger();
        Signal<Integer> mapped = source.map(value -> {
            mapperCalls.incrementAndGet();
            return value * 2;
        });
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> mapped.onDirty(invalidations::incrementAndGet));
        source.set(2);
        source.set(3);

        assertEquals(2, invalidations.get());
        assertEquals(0, mapperCalls.get());
    }

    @Test
    void mapEvaluatesLazilyAndCachesByVersion() {
        MutableSignal<Integer> source = Signal.of(1);
        AtomicInteger mapperCalls = new AtomicInteger();
        Signal<Integer> mapped = source.map(value -> {
            mapperCalls.incrementAndGet();
            return value * 2;
        });
        source.set(2);
        source.set(3);

        assertEquals(0, mapperCalls.get());
        assertEquals(6, mapped.get());
        assertEquals(6, mapped.get());
        assertEquals(1, mapperCalls.get());
        source.set(4);

        assertEquals(8, mapped.get());
        assertEquals(2, mapperCalls.get());
    }

    @Test
    void valueObserverSeesTheLatestSnapshotNotTheTriggeringValue() {
        MutableSignal<Integer> signal = Signal.of(0);
        List<Integer> received = new ArrayList<>();
        AtomicInteger writes = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(() -> {
            if (writes.incrementAndGet() == 1) {
                writeFromAnotherThread(signal, 2);
            }
        }));
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        signal.set(1);

        assertEquals(List.of(2, 2), received);
    }

    private static void writeFromAnotherThread(MutableSignal<Integer> signal, int value) {
        Thread writer = new Thread(() -> signal.set(value), "signal-writer");
        writer.start();
        try {
            writer.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void mapDeliversRepeatedValuesWhenTheSourceChangesButTheResultDoesNot() {
        MutableSignal<Integer> source = Signal.of(0);
        Signal<Integer> parity = source.map(value -> value % 2);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> parity.onDirty(() -> received.add(parity.get())));
        source.set(2);
        source.set(4);

        assertEquals(List.of(0, 0), received);
    }

    @Test
    void subscribeEvaluatesChainOnEachInvalidation() {
        MutableSignal<Integer> source = Signal.of(1);
        Signal<Integer> mapped = source.map(value -> value * 2);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> mapped.onDirty(() -> received.add(mapped.get())));
        source.set(2);
        source.set(3);

        assertEquals(List.of(4, 6), received);
    }

    @Test
    void mapDistinctCutsPropagationWhenValueUnchanged() {
        MutableSignal<Long> tick = Signal.of(0L);
        Signal<Long> day = tick.mapDistinct(value -> value / 24000L);
        AtomicInteger dayInvalidations = new AtomicInteger();
        this.bindings.bind(() -> day.onDirty(dayInvalidations::incrementAndGet));
        for (long i = 1; i <= 20; i++) {
            tick.set(i);
        }

        assertEquals(0, dayInvalidations.get());
        tick.set(24000L);

        assertEquals(1, dayInvalidations.get());
        assertEquals(1L, day.get());
    }

    @Test
    void mapDistinctRecomputesEagerlyOnlyWhileActive() {
        MutableSignal<Long> tick = Signal.of(0L);
        AtomicInteger mapperCalls = new AtomicInteger();
        Signal<Long> day = tick.mapDistinct(value -> {
            mapperCalls.incrementAndGet();
            return value / 24000L;
        });
        tick.set(1L);
        tick.set(2L);

        assertEquals(0, mapperCalls.get());
        Subscription subscription = day.onDirty(() -> {
        });
        int baseline = mapperCalls.get();
        tick.set(3L);
        tick.set(4L);

        assertEquals(baseline + 2, mapperCalls.get());
        subscription.close();
        tick.set(5L);

        assertEquals(baseline + 2, mapperCalls.get());
    }

    @Test
    void mapDistinctStaysPullConsistentWhileInactive() {
        MutableSignal<Long> tick = Signal.of(0L);
        Signal<Long> day = tick.mapDistinct(value -> value / 24000L);
        Signal<String> label = day.map(value -> "day-" + value);

        assertEquals("day-0", label.get());
        tick.set(48000L);

        assertEquals("day-2", label.get());
    }

    @Test
    void mapDistinctChainsIntoLayeredDispatch() {
        MutableSignal<Long> tick = Signal.of(0L);
        Signal<Long> day = tick.mapDistinct(value -> value / 24000L);
        Signal<Long> season = day.mapDistinct(value -> value / 30L);
        AtomicInteger seasonInvalidations = new AtomicInteger();
        this.bindings.bind(() -> season.onDirty(seasonInvalidations::incrementAndGet));
        tick.set(24000L);

        assertEquals(0, seasonInvalidations.get());
        tick.set(24000L * 30);

        assertEquals(1, seasonInvalidations.get());
        assertEquals(1L, season.get());
    }

    @Test
    void combineRecomputesFromBothSources() {
        MutableSignal<Integer> left = Signal.of(1);
        MutableSignal<Integer> right = Signal.of(10);
        Signal<Integer> sum = Signals.combine(left, right, Integer::sum);

        assertEquals(11, sum.get());
        left.set(2);

        assertEquals(12, sum.get());
        right.set(20);

        assertEquals(22, sum.get());
    }

    @Test
    void combineCachesUntilAnySourceChanges() {
        MutableSignal<Integer> left = Signal.of(1);
        MutableSignal<Integer> right = Signal.of(10);
        AtomicInteger combinerCalls = new AtomicInteger();
        Signal<Integer> sum = Signals.combine(left, right, (a, b) -> {
            combinerCalls.incrementAndGet();
            return a + b;
        });

        assertEquals(11, sum.get());
        assertEquals(11, sum.get());
        assertEquals(1, combinerCalls.get());
        right.set(20);

        assertEquals(21, sum.get());
        assertEquals(2, combinerCalls.get());
    }

    @Test
    void combineNotifiesOnAnySourceInvalidation() {
        MutableSignal<Integer> left = Signal.of(1);
        MutableSignal<Integer> right = Signal.of(10);
        Signal<Integer> sum = Signals.combine(left, right, Integer::sum);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> sum.onDirty(invalidations::incrementAndGet));
        left.set(2);
        right.set(20);

        assertEquals(2, invalidations.get());
    }

    @Test
    void combineThreeSources() {
        MutableSignal<Integer> a = Signal.of(1);
        MutableSignal<Integer> b = Signal.of(2);
        MutableSignal<Integer> c = Signal.of(3);
        Signal<Integer> sum = Signals.combine(a, b, c, (x, y, z) -> x + y + z);

        assertEquals(6, sum.get());
        c.set(30);

        assertEquals(33, sum.get());
    }

    @Test
    void observerFailureIsIsolatedFromTheWriter() {
        MutableSignal<String> signal = Signal.of("initial");
        IllegalStateException failure = new IllegalStateException("broken observer");
        List<String> received = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> {
            throw failure;
        }));
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            signal.set("changed");

            assertEquals(List.of(failure), probe.failures(), "观察者异常应交给统一异常处理器");
        }

        assertEquals(List.of("changed"), received, "失败的观察者不应挡住后续订阅者");
        assertEquals("changed", signal.get());
    }

    @Test
    void observerFailureDoesNotBlockFurtherWrites() {
        MutableSignal<Integer> signal = Signal.of(0);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> {
            throw new IllegalStateException("broken observer");
        }));
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            signal.set(1);
            signal.set(2);

            assertEquals(2, probe.failures().size());
        }

        assertEquals(List.of(1, 2), received);
    }

    @Test
    void brokenMapDistinctMapperDoesNotBreakTheSource() {
        MutableSignal<Long> tick = Signal.of(0L);
        Signal<Long> day = tick.mapDistinct(value -> {
            if (value > 0L) {
                throw new IllegalStateException("broken mapper");
            }
            return value / 24000L;
        });
        this.bindings.bind(() -> day.onDirty(() -> {
        }));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            tick.set(1L);

            assertEquals(1, probe.failures().size(), "mapper 失败应被上报");
        }

        assertEquals(1L, tick.get(), "上游写入不应被下游 mapper 的失败回滚");
    }

    @Test
    void weakSubscriberFailureIsIsolatedFromTheWriter() {
        MutableSignal<Integer> signal = Signal.of(0);
        String message = "broken binding";
        Subscription subscription = signal.onDirty(() -> {
            throw new IllegalStateException(message);
        });
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            signal.set(1);

            assertEquals(1, probe.failures().size());
        }

        assertEquals(1, signal.get());
        Reference.reachabilityFence(subscription);
    }

    @Test
    void rejectsNullCallbacks() {
        MutableSignal<Integer> signal = Signal.of(1);

        assertThrows(NullPointerException.class, () -> signal.onDirty(null));
        assertThrows(NullPointerException.class, () -> signal.map(null));
        assertThrows(NullPointerException.class, () -> signal.mapDistinct(null));
    }

    @Test
    void duplicateListenersHaveIndependentSubscriptions() {
        MutableSignal<Integer> signal = Signal.of(0);
        List<Integer> received = new ArrayList<>();
        Runnable listener = () -> received.add(signal.get());
        Subscription first = signal.onDirty(listener);
        Subscription second = signal.onDirty(listener);
        first.close();
        first.close();
        signal.set(1);

        assertTrue(first.isClosed());
        assertEquals(List.of(1), received);
        second.close();
    }
}
