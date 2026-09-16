package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickingSignalTest {

    private final Bindings bindings = new Bindings();

    @Test
    void schedulingStartsOnFirstSubscriberAndStopsOnLast() {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);

        assertEquals(0, ticker.starts(), "无订阅者时不应占用调度任务");
        Subscription first = signal.onDirty(() -> {
        });
        Subscription second = signal.onDirty(() -> {
        });

        assertEquals(1, ticker.starts(), "多个订阅者共享一个调度任务");
        assertEquals(0, ticker.cancels());
        first.close();

        assertEquals(0, ticker.cancels(), "还有订阅者时不应取消");
        second.close();

        assertEquals(1, ticker.cancels());
    }

    @Test
    void schedulingResumesAfterAllSubscribersLeft() {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);
        signal.onDirty(() -> {
        }).close();
        this.bindings.bind(() -> signal.onDirty(() -> {
        }));

        assertEquals(2, ticker.starts());
    }

    @Test
    void tickAdvancesValueAndNotifies() {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);
        List<Long> received = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        ticker.tick(7L);
        ticker.tick(8L);

        assertEquals(List.of(7L, 8L), received);
        assertEquals(8L, signal.get());
    }

    @Test
    void repeatedTickValueProducesNoInvalidation() {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        ticker.tick(5L);
        ticker.tick(5L);

        assertEquals(1, invalidations.get());
    }

    @Test
    void tickerStartFailureRollsBackRegistration() {
        ManualTicker ticker = new ManualTicker();
        IllegalStateException failure = new IllegalStateException("scheduler unavailable");
        ticker.failNextStart(failure);
        TickingSignal signal = new TickingSignal(ticker);

        assertSame(failure, assertThrows(RuntimeException.class, () -> signal.onDirty(() -> {
        })));

        assertEquals(0, signal.entryCount(), "激活失败不应留下订阅");
        List<Long> received = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        ticker.tick(3L);

        assertEquals(List.of(3L), received);
    }

    @Test
    void lateTickAfterCancellationIsHarmless() {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);
        List<Long> received = new ArrayList<>();
        Subscription subscription = this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        ticker.tick(1L);
        subscription.close();
        ticker.tickIgnoringCancellation(2L);

        assertEquals(List.of(1L), received, "取消后不应再送达");
        assertEquals(2L, signal.get(), "迟到的 tick 仍可推进快照, 由拉取路径自愈");
    }

    @Test
    void valueFreezesWhileUnobserved() {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);
        Subscription subscription = signal.onDirty(signal::get);
        ticker.tick(9L);
        subscription.close();

        assertEquals(9L, signal.get(), "无订阅期间值冻结在最后一次观察");
    }

    @Test
    void valueContinuesAcrossSchedulingEpochs() {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);
        Subscription first = signal.onDirty(() -> {
        });
        ticker.tick(5L);
        first.close();
        this.bindings.bind(() -> signal.onDirty(() -> {
        }));
        ticker.tick(1L);
        ticker.tick(2L);

        assertEquals(7L, signal.get(), "第二段调度的计数应叠在冻结值之上");
    }

    @Test
    void aLateTickFromTheCancelledEpochNeverMovesTheValueBackwards() {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);
        List<Long> received = new ArrayList<>();
        Subscription first = this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        ticker.tick(5L);
        first.close();
        LongConsumer lateEpoch = ticker.callback();
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        lateEpoch.accept(6L);
        ticker.tick(1L);
        ticker.tick(2L);

        assertEquals(List.of(5L, 6L, 7L), received, "迟到的一拍只是把 6 提前算出来, 之后不跳不退");
        assertEquals(7L, signal.get());
    }

    @Test
    void aStaleTickArrivingAfterTheNewEpochHasPassedItIsDropped() {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);
        Subscription first = this.bindings.bind(() -> signal.onDirty(() -> {
        }));
        ticker.tick(5L);
        first.close();
        LongConsumer lateEpoch = ticker.callback();
        List<Long> received = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        ticker.tick(1L);
        ticker.tick(2L);
        ticker.tick(3L);
        lateEpoch.accept(6L);

        assertEquals(8L, signal.get(), "走过的值不回退");
        assertEquals(List.of(6L, 7L, 8L), received, "被丢掉的一拍不通知");
    }

    @Test
    void epochsRacingOnDifferentThreadsStillCountMonotonically() throws InterruptedException {
        ManualTicker ticker = new ManualTicker();
        TickingSignal signal = new TickingSignal(ticker);
        Subscription first = this.bindings.bind(() -> signal.onDirty(() -> {
        }));
        ticker.tick(5L);
        first.close();
        LongConsumer lateEpoch = ticker.callback();
        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        CountDownLatch start = new CountDownLatch(1);
        Thread stale = new Thread(() -> {
            await(start);
            lateEpoch.accept(6L);
        });
        Thread fresh = new Thread(() -> {
            await(start);
            for (long tick = 1L; tick <= 3L; tick++) ticker.tick(tick);
        });
        stale.start();
        fresh.start();
        start.countDown();
        stale.join();
        fresh.join();

        assertEquals(8L, signal.get(), "两段各自叠在自己的起点上, 终值就是 5 + 3");
        for (long value : received) {
            assertTrue(value >= 6L && value <= 8L, "看到了段外的值: " + value);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void tickingIsASharedSingleton() {
        assertSame(Signals.ticking(), Signals.ticking());
        assertNotNull(Signals.ticking().get());
    }

    @Test
    void everyTicksInvalidatesOnlyOnPeriodBoundary() {
        TickingTestSupport.install();
        try {
            Signal<Long> quarter = Signals.everyTicks(4);
            List<Long> received = new ArrayList<>();
            this.bindings.bind(() -> quarter.onDirty(() -> received.add(quarter.get())));
            TickingTestSupport.advance(3);

            assertEquals(List.of(), received, "不足一个周期不应失效");
            TickingTestSupport.advance(5);

            assertEquals(List.of(1L, 2L), received, "每满一个周期失效一次");
        } finally {
            TickingTestSupport.restore();
        }
    }

    @Test
    void periodicViewsAreSharedWhileHeldAndDroppedOnceUnreachable() {
        TickingSignal signal = new TickingSignal(new ManualTicker());
        Signal<Long> held = signal.every(4L);

        assertSame(held, signal.every(4L), "有人持有时同周期应复用同一节点");
        WeakReference<Signal<Long>> probe = new WeakReference<>(signal.every(8L));
        for (long period = 16L; period <= 64L; period++) {
            signal.every(period);
        }
        GcSupport.awaitCollected(probe);
        signal.every(4L);

        assertEquals(1, signal.periodicViewCount(), "没人持有的周期不应留在缓存里");
        assertSame(held, signal.every(4L), "仍被持有的周期必须留下");
    }

    private static final class ManualTicker implements TickingSignal.Ticker {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();
        private LongConsumer callback;
        private boolean cancelled;
        private RuntimeException startFailure;
        @Override
        @NotNull
        public Handle start(@NotNull LongConsumer onTick) {
            if (this.startFailure != null) {
                RuntimeException failure = this.startFailure;
                this.startFailure = null;
                throw failure;
            }
            this.starts.incrementAndGet();
            this.callback = onTick;
            this.cancelled = false;
            return () -> {
                this.cancels.incrementAndGet();
                this.cancelled = true;
            };
        }
        void failNextStart(RuntimeException failure) {
            this.startFailure = failure;
        }
        int starts() {
            return this.starts.get();
        }
        int cancels() {
            return this.cancels.get();
        }
        void tick(long value) {
            assertTrue(!this.cancelled, "调度任务已取消");
            this.callback.accept(value);
        }
        void tickIgnoringCancellation(long value) {
            this.callback.accept(value);
        }
        LongConsumer callback() {
            return this.callback;
        }
    }
}
