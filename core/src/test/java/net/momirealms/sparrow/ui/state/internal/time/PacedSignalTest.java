package net.momirealms.sparrow.ui.state.internal.time;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.ExceptionHandlerProbe;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import net.momirealms.sparrow.ui.state.internal.time.DelayTestSupport.Pacing;
import net.momirealms.sparrow.ui.state.internal.time.DelayTestSupport.TimeBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacedSignalTest {

    private static final String COMBINATIONS = "net.momirealms.sparrow.ui.state.internal.time.DelayTestSupport#combinations";
    private final Bindings bindings = new Bindings();

    @BeforeEach
    void installDelayers() {
        DelayTestSupport.install();
    }

    @AfterEach
    void restoreDelayers() {
        DelayTestSupport.restore();
    }

    @ParameterizedTest
    @MethodSource(COMBINATIONS)
    void deadDownstreamIsSweptOnTheNextUpstreamChange(TimeBase base, Pacing pacing) {
        MutableSignal<Integer> input = Signal.of(0);
        AbstractSignal<Integer> internalInput = (AbstractSignal<Integer>) input;
        Signal<Integer> paced = pacing.apply(base, input, 2);
        WeakReference<?> probe = subscribeAndDrop(paced);

        assertEquals(1, SignalTestAccess.entryCount(internalInput), "有订阅时节点挂在上游上");
        GcSupport.awaitCollected(probe);
        input.set(1);

        assertEquals(0, SignalTestAccess.entryCount(internalInput), "下游死后的第一次上游失效就该把上游订阅收掉");
        assertFalse(base.delayer().scheduled(), "不该为死下游排任务");
    }

    @ParameterizedTest
    @MethodSource(COMBINATIONS)
    void aTaskFiringAfterTheDownstreamDiedEmitsNothingAndReleasesTheUpstream(TimeBase base, Pacing pacing) {
        MutableSignal<Integer> input = Signal.of(0);
        AbstractSignal<Integer> internalInput = (AbstractSignal<Integer>) input;
        Signal<Integer> paced = pacing.apply(base, input, 3);
        WeakReference<?> probe = subscribeAndDrop(paced);
        input.set(1);

        assertTrue(base.delayer().scheduled(), "失效之后应当有任务等着触发");
        GcSupport.awaitCollected(probe);
        base.advance(3);

        assertEquals(0, SignalTestAccess.entryCount(internalInput));
        assertFalse(base.delayer().scheduled());
    }

    private static WeakReference<?> subscribeAndDrop(Signal<?> signal) {
        Object captured = new Object();
        signal.onDirty(captured::hashCode);
        return new WeakReference<>(captured);
    }

    @ParameterizedTest
    @MethodSource(COMBINATIONS)
    void closingTheLastSubscriptionCancelsThePendingTaskAndReleasesTheUpstream(TimeBase base, Pacing pacing) {
        MutableSignal<Integer> input = Signal.of(0);
        AbstractSignal<Integer> internalInput = (AbstractSignal<Integer>) input;
        Signal<Integer> paced = pacing.apply(base, input, 3);
        List<Integer> received = new ArrayList<>();
        Subscription subscription = paced.onDirty(() -> received.add(paced.get()));
        input.set(1);
        input.set(2);

        assertTrue(base.delayer().scheduled());
        subscription.close();

        assertFalse(base.delayer().scheduled(), "最后一个订阅走了, 待发任务也要取消");
        assertEquals(0, SignalTestAccess.entryCount(internalInput), "上游订阅要关掉");
        base.advance(3);

        assertEquals(pacing == Pacing.THROTTLE ? List.of(1) : List.of(), received, "退订后不再发出");
        assertEquals(2, paced.get(), "退订后退化为透传");
    }

    @ParameterizedTest
    @MethodSource(COMBINATIONS)
    void hammeringWritersAndAClockThreadSettleOnTheFinalValue(TimeBase base, Pacing pacing) throws InterruptedException {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> paced = pacing.apply(base, input, 2);
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> paced.onDirty(notifications::incrementAndGet));
        int writers = 4;
        int iterations = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicBoolean clockRunning = new AtomicBoolean(true);
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            for (int i = 0; i < writers; i++) {
                int offset = i * iterations;
                Thread writer = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int j = 0; j < iterations; j++) {
                        input.set(offset + j);
                    }
                    done.countDown();
                });
                writer.start();
            }
            Thread clock = new Thread(() -> {
                while (clockRunning.get()) {
                    base.advance(1);
                    Thread.onSpinWait();
                }
            });
            clock.start();
            start.countDown();

            assertTrue(done.await(10, TimeUnit.SECONDS));
            clockRunning.set(false);
            clock.join();
            base.advance(5);

            assertTrue(notifications.get() >= 1);
            assertEquals(input.get(), paced.get(), "静默后读到的必须是上游终值");
            assertEquals(List.of(), probe.failures());
        }
    }

    @ParameterizedTest
    @MethodSource(COMBINATIONS)
    void aWriteRacingTheSubscriptionIsFoldedIntoTheBaselineAndNotReplayed(TimeBase base, Pacing pacing) throws InterruptedException {
        MutableSignal<Integer> input = Signal.of(0);
        AtomicReference<Thread> subscriber = new AtomicReference<>();
        AtomicReference<Thread> writer = new AtomicReference<>();
        CountDownLatch subscriberInsideMapper = new CountDownLatch(1);
        CountDownLatch releaseSubscriber = new CountDownLatch(1);
        CountDownLatch writerInsideMapper = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        Signal<Integer> source = input.mapDistinct(value -> {
            Thread current = Thread.currentThread();
            if (current == subscriber.get() && value == 0) {
                subscriberInsideMapper.countDown();
                await(releaseSubscriber);
            } else if (current == writer.get()) {
                writerInsideMapper.countDown();
                await(releaseWriter);
            }
            return value;
        });
        Signal<Integer> paced = pacing.apply(base, source, 2);
        List<Integer> received = new ArrayList<>();
        subscriber.set(new Thread(() -> this.bindings.bind(() -> paced.onDirty(() -> received.add(paced.get())))));
        writer.set(new Thread(() -> input.set(1)));
        subscriber.get().start();
        await(subscriberInsideMapper);
        writer.get().start();
        await(writerInsideMapper);
        releaseSubscriber.countDown();
        subscriber.get().join();

        assertEquals(1, paced.get(), "基线已经包含了并发写入的值");
        releaseWriter.countDown();
        writer.get().join();
        base.advance(2);

        assertEquals(List.of(), received, "并进基线的写入不能再补发");
        input.set(2);
        base.advance(2);

        assertEquals(List.of(2), received);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "等待超时, 线程没有停在预期位置");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @ParameterizedTest
    @MethodSource(COMBINATIONS)
    void aDistinctMapperDownstreamRunsPerEmissionNotPerKeystroke(TimeBase base, Pacing pacing) {
        MutableSignal<String> input = Signal.of("");
        AtomicInteger mapperCalls = new AtomicInteger();
        Signal<Integer> lengths = pacing.apply(base, input, 3).mapDistinct(text -> {
            mapperCalls.incrementAndGet();
            return text.length();
        });
        this.bindings.bind(() -> lengths.onDirty(() -> {
        }));

        assertEquals(1, mapperCalls.get(), "订阅时算一次基线");
        for (String typed : List.of("a", "ab", "abc", "abcd", "abcde")) {
            input.set(typed);
        }
        base.advance(3);
        int emissions = pacing == Pacing.DEBOUNCE ? 1 : 2;

        assertEquals(1 + emissions, mapperCalls.get());
        assertEquals(5, lengths.get());
    }

    @ParameterizedTest
    @MethodSource(COMBINATIONS)
    void aRejectedScheduleIsReportedAndTheNextChangeRetries(TimeBase base, Pacing pacing) {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> paced = pacing.apply(base, input, 2);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> paced.onDirty(() -> received.add(paced.get())));
        IllegalStateException rejected = new IllegalStateException("scheduler rejected");
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            base.delayer().failNextSchedule(rejected);
            input.set(1);

            assertEquals(List.of(rejected), probe.failures(), "调度器的异常沿上游派发路径上报");
            assertEquals(List.of(), received, "排不进任务就不发出");
            assertEquals(0, paced.get(), "快照不动");
            assertFalse(base.delayer().scheduled());
            input.set(2);
            base.advance(2);

            assertEquals(List.of(2), received);
            assertEquals(List.of(rejected), probe.failures());
        }
    }

    @ParameterizedTest
    @MethodSource(COMBINATIONS)
    void aMapperFailingInsideTheFiredTaskIsReportedOnceAndTheNextChangeRecovers(TimeBase base, Pacing pacing) {
        IllegalStateException failure = new IllegalStateException("mapper failed");
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> paced = pacing.apply(base, input.map(value -> {
            if (value == 2) throw failure;
            return value;
        }), 3);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> paced.onDirty(() -> received.add(paced.get())));
        List<Integer> primed = pacing == Pacing.THROTTLE ? List.of(1) : List.of();
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            input.set(1);
            input.set(2);

            assertEquals(primed, received);
            base.advance(3);

            assertEquals(List.of(failure), probe.failures(), "到点拍快照抛出, 恰好上报一次");
            assertEquals(primed, received, "没有成功通知");
            input.set(3);
            base.advance(3);

            assertEquals(pacing == Pacing.THROTTLE ? List.of(1, 3) : List.of(3), received, "下一次合法失效照常发出");
            assertEquals(List.of(failure), probe.failures());
        }
    }

    @ParameterizedTest
    @MethodSource(COMBINATIONS)
    void activationRollsBackWhenReadingTheSourceFails(TimeBase base, Pacing pacing) {
        MutableSignal<Integer> input = Signal.of(0);
        AbstractSignal<Integer> internalInput = (AbstractSignal<Integer>) input;
        IllegalStateException failure = new IllegalStateException("mapper failed");
        Signal<Integer> paced = pacing.apply(base, input.map(value -> {
            throw failure;
        }), 2);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> paced.onDirty(() -> {
        }));

        assertSame(failure, thrown);
        assertEquals(0, SignalTestAccess.entryCount(internalInput), "激活失败要把已挂的上游撤掉");
        assertEquals(0, SignalTestAccess.entryCount(paced), "失败的订阅不该留在节点上");
    }

    @Test
    void nonPositiveDelaysAreRejectedUpFront() {
        MutableSignal<Integer> input = Signal.of(0);

        assertThrows(IllegalArgumentException.class, () -> input.debounce(0));
        assertThrows(IllegalArgumentException.class, () -> input.debounceMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> input.throttle(0));
        assertThrows(IllegalArgumentException.class, () -> input.throttleMillis(-5));
    }
}
