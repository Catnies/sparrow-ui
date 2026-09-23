package net.momirealms.sparrow.ui.state.internal;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.scheduler.BukkitSchedulerAdapter;
import net.momirealms.sparrow.ui.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.state.internal.time.Delayer;
import net.momirealms.sparrow.ui.state.internal.time.TickingSignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalSchedulingTest {
    private ServerMock server;
    private BukkitSchedulerAdapter scheduler;
    private SchedulerAdapter previousScheduler;
    private Field schedulerField;

    @BeforeEach
    void setUp() throws Exception {
        this.server = MockBukkit.mock();
        this.scheduler = new BukkitSchedulerAdapter(MockBukkit.createMockPlugin());
        this.schedulerField = SparrowUI.class.getDeclaredField("scheduler");
        this.schedulerField.setAccessible(true);
        this.previousScheduler = (SchedulerAdapter) this.schedulerField.get(SparrowUI.getInstance());
        this.schedulerField.set(SparrowUI.getInstance(), this.scheduler);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.scheduler.shutdownScheduler();
        this.scheduler.shutdownExecutor();
        this.schedulerField.set(SparrowUI.getInstance(), this.previousScheduler);
        MockBukkit.unmock();
    }

    @Test
    void tickClockUsesServerTicksAndResumesFromItsFrozenValue() {
        TickingSignal signal = new TickingSignal(TickingSignal.platformTicker());
        List<Long> values = new ArrayList<>();
        Subscription first = signal.onDirty(() -> values.add(signal.get()));
        this.server.getScheduler().performTicks(3);
        first.close();
        this.server.getScheduler().performTicks(2);
        assertEquals(List.of(1L, 2L, 3L), values);
        try (Subscription second = signal.onDirty(() -> values.add(signal.get()))) {
            this.server.getScheduler().performOneTick();
            assertEquals(List.of(1L, 2L, 3L, 4L), values);
        }
    }

    @Test
    void tickDelayKeepsItsUnitsAndCanBeCancelled() {
        AtomicInteger calls = new AtomicInteger();
        Delayer.ticks().schedule(calls::incrementAndGet, 3);
        Delayer.Handle cancelled = Delayer.ticks().schedule(calls::incrementAndGet, 2);
        cancelled.cancel();
        this.server.getScheduler().performTicks(2);
        assertEquals(0, calls.get());
        this.server.getScheduler().performOneTick();
        assertEquals(1, calls.get());
    }

    @Test
    void millisClockAndDelayRunOffThreadWithoutServerTicks() throws InterruptedException {
        CountDownLatch delayed = new CountDownLatch(1);
        CountDownLatch ticked = new CountDownLatch(2);
        AtomicReference<Thread> worker = new AtomicReference<>();
        Delayer.millis().schedule(() -> {
            worker.set(Thread.currentThread());
            delayed.countDown();
        }, 20);
        TickingSignal.Ticker.Handle handle = TickingSignal.millisTicker(50).start(value -> ticked.countDown());
        try {
            assertTrue(delayed.await(5, TimeUnit.SECONDS));
            assertTrue(ticked.await(5, TimeUnit.SECONDS));
            assertFalse(Thread.currentThread() == worker.get());
        } finally {
            handle.cancel();
        }
    }

    @Test
    void slowMillisCallbacksStaySerialAndCountEveryDeliveredTick() throws Exception {
        AtomicReference<Runnable> callback = this.captureRepeating();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        List<Long> values = new ArrayList<>();
        TickingSignal.Ticker.Handle handle = TickingSignal.millisTicker(50).start(value -> {
            values.add(value);
            if (value == 1L) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(callback.get());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = workers.submit(() -> {
                    secondStarted.countDown();
                    callback.get().run();
                });
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                assertEquals(List.of(1L, 2L), values);
            } finally {
                release.countDown();
                handle.cancel();
            }
        }
    }

    @Test
    void queuedMillisCallbacksCannotAdvanceTheCancelledOrNextEpoch() throws Exception {
        AtomicReference<Runnable> callback = this.captureRepeating();
        TickingSignal signal = new TickingSignal(TickingSignal.millisTicker(50));
        Subscription first = signal.onDirty(() -> {});
        callback.get().run();
        Runnable stale = callback.get();
        first.close();
        stale.run();
        assertEquals(1L, signal.get());
        try (Subscription second = signal.onDirty(() -> {})) {
            stale.run();
            assertEquals(1L, signal.get());
            callback.get().run();
            assertEquals(2L, signal.get());
        }
    }

    @Test
    void stoppingTheSchedulerCancelsPendingWallClockTasks() {
        AtomicInteger calls = new AtomicInteger();
        SchedulerTask delayed = this.scheduler.asyncLater(calls::incrementAndGet, 1, TimeUnit.DAYS);
        SchedulerTask repeating = this.scheduler.asyncRepeating(calls::incrementAndGet, 1, 1, TimeUnit.DAYS);
        this.scheduler.shutdownScheduler();
        this.scheduler.shutdownExecutor();
        assertTrue(delayed.cancelled());
        assertTrue(repeating.cancelled());
        assertEquals(0, calls.get());
    }

    private AtomicReference<Runnable> captureRepeating() throws IllegalAccessException {
        AtomicReference<Runnable> callback = new AtomicReference<>();
        SchedulerAdapter recording = (SchedulerAdapter) Proxy.newProxyInstance(
                this.getClass().getClassLoader(), new Class<?>[]{SchedulerAdapter.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("asyncRepeating")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    assertEquals(50L, arguments[1]);
                    assertEquals(50L, arguments[2]);
                    assertEquals(TimeUnit.MILLISECONDS, arguments[3]);
                    callback.set((Runnable) arguments[0]);
                    return new SchedulerTask() {
                        private final AtomicBoolean cancelled = new AtomicBoolean();

                        @Override
                        public void cancel() {
                            this.cancelled.set(true);
                        }

                        @Override
                        public boolean cancelled() {
                            return this.cancelled.get();
                        }
                    };
                }
        );
        this.schedulerField.set(SparrowUI.getInstance(), recording);
        return callback;
    }
}
