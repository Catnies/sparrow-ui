package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Bindings;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalThreadContractTest {

    private final Bindings bindings = new Bindings();

    @Test
    void concurrentSetAndGetStayConsistent() throws InterruptedException {
        MutableSignal<Integer> signal = Signal.of(0);
        int writers = 4;
        int iterations = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        for (int i = 0; i < writers; i++) {
            int base = i * iterations;
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < iterations; j++) {
                    signal.set(base + j);
                }
                done.countDown();
            });
            writer.start();
        }
        start.countDown();
        while (done.getCount() > 0) {
            Integer value = signal.get();

            assertTrue(value >= 0 && value < writers * iterations, "读到非法值: " + value);
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void concurrentUpdatesNeverLoseIncrements() throws InterruptedException {
        MutableSignal<Integer> signal = Signal.of(0);
        int writers = 4;
        int iterations = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        for (int i = 0; i < writers; i++) {
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < iterations; j++) {
                    signal.update(value -> value + 1);
                }
                done.countDown();
            });
            writer.start();
        }
        start.countDown();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(writers * iterations, signal.get());
    }

    @Test
    void invalidationFromBackgroundThreadReachesSubscriber() throws InterruptedException {
        MutableSignal<String> signal = Signal.of("initial");
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        this.bindings.bind(() -> signal.onDirty(() -> {
            value.set(signal.get());
            callbackThread.set(Thread.currentThread());
            received.countDown();
        }));
        Thread updater = new Thread(() -> signal.set("from-background"));
        updater.start();
        updater.join();

        assertTrue(received.await(1, TimeUnit.SECONDS));
        assertEquals("from-background", value.get());
        assertSame(updater, callbackThread.get(), "值回调应在触发更新的线程直呼");
    }

    @Test
    void asyncGetNeverBlocksWhileLoaderPending() {
        ManualExecutor executor = new ManualExecutor();
        AsyncSignal<String> signal = Signal.async("placeholder", executor, () -> "loaded");

        assertEquals("placeholder", signal.get());
        assertEquals(1, executor.pending());
    }

    @Test
    void asyncPublishesLoadedValueAndNotifies() {
        ManualExecutor executor = new ManualExecutor();
        AsyncSignal<String> signal = Signal.async("placeholder", executor, () -> "loaded");
        List<String> received = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        executor.drain();

        assertEquals("loaded", signal.get());
        assertEquals(List.of("loaded"), received);
    }

    @Test
    void asyncCoalescesInvalidationsWhileLoading() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.async(0, executor, loads::incrementAndGet);
        executor.drain();

        assertEquals(1, loads.get());
        signal.dirty();
        signal.dirty();
        signal.dirty();
        executor.drain();

        assertEquals(3, loads.get(), "进行中的失效应合并为一轮补载");
        assertEquals(3, signal.get());
    }

    @Test
    void asyncSkipsNotificationWhenReloadYieldsEqualValue() {
        ManualExecutor executor = new ManualExecutor();
        AsyncSignal<String> signal = Signal.async("placeholder", executor, () -> "stable");
        executor.drain();
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        signal.dirty();
        executor.drain();

        assertEquals(0, invalidations.get());
        assertEquals("stable", signal.get());
    }

    @Test
    void asyncKeepsOldValueWhenLoaderFails() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("db down");
        AsyncSignal<String> signal = Signal.async("placeholder", executor, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw failure;
            }
            return "recovered";
        });
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            executor.drain();

            assertEquals(List.of(failure), probe.failures(), "装载失败应上报给统一异常处理器");
        }

        assertEquals("placeholder", signal.get());
        signal.dirty();
        executor.drain();

        assertEquals("recovered", signal.get());
    }

    @Test
    void asyncStaysUsableAfterExecutorRejection() {
        ManualExecutor delegate = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.async(0, command -> {
            if (rejections.get() > 0) {
                rejections.decrementAndGet();
                throw new RejectedExecutionException("busy");
            }
            delegate.execute(command);
        }, loads::incrementAndGet);
        delegate.drain();

        assertEquals(1, loads.get());
        rejections.set(1);
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            signal.dirty();

            assertEquals(1, probe.failures().size(), "调度被拒应上报给统一异常处理器");
        }
        signal.dirty();
        delegate.drain();

        assertEquals(2, loads.get(), "拒绝之后状态机必须回到可调度状态");
    }

    @Test
    void derivedGetIsSafeUnderConcurrentInvalidation() throws InterruptedException {
        MutableSignal<Integer> source = Signal.of(0);
        Signal<Integer> derived = source.map(value -> value * 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 1; i <= 2000; i++) {
                source.set(i);
            }
            done.countDown();
        });
        writer.start();
        start.countDown();
        while (done.getCount() > 0) {
            Integer value = derived.get();

            assertEquals(0, value % 2, "派生值应始终是某个合法快照的映射");
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(4000, derived.get());
    }
}
