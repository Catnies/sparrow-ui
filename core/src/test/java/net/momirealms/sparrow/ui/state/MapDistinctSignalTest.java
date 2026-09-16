package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Bindings;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDistinctSignalTest {

    private static final long JOIN_TIMEOUT_MILLIS = 30_000L;
    private final Bindings bindings = new Bindings();

    @Test
    void mapperMayReadAnotherMapDistinct() {
        MutableSignal<Integer> base = Signal.of(1);
        Signal<Integer> doubled = base.mapDistinct(value -> value * 2);
        MutableSignal<Integer> source = Signal.of(10);
        Signal<Integer> combined = source.mapDistinct(value -> value + doubled.get());

        assertEquals(12, combined.get());
        source.set(20);

        assertEquals(22, combined.get());
    }

    @Test
    void readingAnotherSignalInTheMapperDoesNotSubscribeToIt() {
        MutableSignal<Integer> extra = Signal.of(100);
        MutableSignal<Integer> source = Signal.of(1);
        Signal<Integer> combined = source.mapDistinct(value -> value + extra.get());
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> combined.onDirty(invalidations::incrementAndGet));

        assertEquals(101, combined.get());
        extra.set(200);

        assertEquals(0, invalidations.get());
        assertEquals(101, combined.get());
        source.set(2);

        assertEquals(1, invalidations.get());
        assertEquals(202, combined.get());
    }

    @Test
    void mapperFailureLeavesTheCacheUntouched() {
        MutableSignal<Integer> source = Signal.of(1);
        AtomicReference<RuntimeException> boom = new AtomicReference<>();
        Signal<Integer> mapped = source.mapDistinct(value -> {
            RuntimeException failure = boom.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return value * 2;
        });

        assertEquals(2, mapped.get());
        source.set(5);
        boom.set(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, mapped::get);
        assertEquals(10, mapped.get(), "抛出那次没有发布任何东西, 下一次读照常重算");
    }

    @Test
    void concurrentReadsAndWritesKeepValueAndVersionConsistent() throws InterruptedException {
        MutableSignal<Integer> source = Signal.of(0);
        Signal<Integer> doubled = source.mapDistinct(value -> value * 2);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) doubled;
        int rounds = 5000;
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        threads.add(new Thread(() -> {
            if (!awaitStart(start)) return;
            for (int i = 1; i <= rounds; i++) {
                source.set(i);
            }
        }, "mapdistinct-writer"));
        for (int reader = 0; reader < 4; reader++) {
            threads.add(new Thread(() -> {
                if (!awaitStart(start)) return;
                long previous = 0L;
                for (int i = 0; i < rounds; i++) {
                    long version = internal.version();
                    if (version < previous) {
                        failure.compareAndSet(null, "版本回退 " + previous + " -> " + version);
                    }
                    previous = version;
                    doubled.get();
                }
            }, "mapdistinct-reader-" + reader));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        start.countDown();
        joinAll(threads);

        assertNull(failure.get());
        assertEquals(rounds * 2, doubled.get());
        long version = internal.version();

        assertTrue(version >= 1 && version <= rounds + 1, "版本记的是重算过多少次, 不是上游变过多少次: " + version);
    }

    @Test
    void theLastChangeAlwaysNotifiesAfterConcurrentTraffic() throws InterruptedException {
        MutableSignal<Integer> source = Signal.of(0);
        Signal<Integer> doubled = source.mapDistinct(value -> value * 2);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> doubled.onDirty(invalidations::incrementAndGet));
        int rounds = 2000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int writer = 0; writer < 3; writer++) {
            int offset = writer * rounds;
            threads.add(new Thread(() -> {
                if (!awaitStart(start)) return;
                for (int i = 1; i <= rounds; i++) {
                    source.set(offset + i);
                }
            }, "mapdistinct-writer-" + writer));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        start.countDown();
        joinAll(threads);
        int before = invalidations.get();
        source.set(Integer.MAX_VALUE);

        assertEquals(before + 1, invalidations.get(), "静默之后的这一次变化必须恰好通知一遍");
        assertEquals(Integer.MAX_VALUE * 2, doubled.get());
    }

    @Test
    void concurrentFirstReadsAgreeOnOneValue() throws InterruptedException {
        MutableSignal<Integer> source = Signal.of(7);
        AtomicInteger mapperCalls = new AtomicInteger();
        Signal<Integer> mapped = source.mapDistinct(value -> {
            mapperCalls.incrementAndGet();
            return value * 3;
        });
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int reader = 0; reader < 8; reader++) {
            threads.add(new Thread(() -> {
                if (!awaitStart(start)) return;
                int value = mapped.get();
                if (value != 21) {
                    failure.compareAndSet(null, "读到 " + value);
                }
            }, "mapdistinct-first-reader-" + reader));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        start.countDown();
        joinAll(threads);

        assertNull(failure.get());
        assertTrue(mapperCalls.get() >= 1, "至少算过一次");
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(JOIN_TIMEOUT_MILLIS);

            assertTrue(!thread.isAlive(), thread.getName() + " 没能在 " + JOIN_TIMEOUT_MILLIS + "ms 内结束");
        }
    }

    private static boolean awaitStart(CountDownLatch start) {
        try {
            return start.await(JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
