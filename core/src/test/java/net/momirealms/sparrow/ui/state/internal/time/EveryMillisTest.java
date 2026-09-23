package net.momirealms.sparrow.ui.state.internal.time;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EveryMillisTest {

    private final Bindings bindings = new Bindings();

    @BeforeEach
    void installMillis() {
        TickingTestSupport.installMillis(100, 200);
    }

    @AfterEach
    void restoreMillis() {
        TickingTestSupport.restore();
    }

    @Test
    void firstSubscriberStartsTheTaskAndTheLastOneCancelsIt() {
        Signal<Long> clock = Signals.everyMillis(100);

        assertFalse(TickingTestSupport.millisScheduled(100), "没人订阅不起任务");
        Subscription first = clock.onDirty(() -> {
        });
        Subscription second = clock.onDirty(() -> {
        });

        assertTrue(TickingTestSupport.millisScheduled(100));
        assertEquals(1, TickingTestSupport.millisStarts(100), "多个订阅者共享一个任务");
        first.close();

        assertTrue(TickingTestSupport.millisScheduled(100), "还有订阅者时不取消");
        second.close();

        assertFalse(TickingTestSupport.millisScheduled(100), "最后一个走了就取消");
    }

    @Test
    void samePeriodSharesOneInstanceAndOtherPeriodsStayIndependent() {
        Signal<Long> hundred = Signals.everyMillis(100);
        Signal<Long> twoHundred = Signals.everyMillis(200);

        assertSame(hundred, Signals.everyMillis(100), "同周期共享一个实例");
        assertNotSame(hundred, twoHundred);
        this.bindings.bind(() -> hundred.onDirty(() -> {
        }));

        assertTrue(TickingTestSupport.millisScheduled(100));
        assertFalse(TickingTestSupport.millisScheduled(200), "别的周期各自独立, 没人订阅就不起任务");
    }

    @Test
    void valueCountsPeriodsAndKeepsCountingAcrossAStop() {
        Signal<Long> clock = Signals.everyMillis(100);
        Subscription subscription = clock.onDirty(() -> {
        });
        TickingTestSupport.advanceMillis(100);
        TickingTestSupport.advanceMillis(100);
        TickingTestSupport.advanceMillis(100);

        assertEquals(3L, clock.get(), "值是有订阅以来经过的周期数");
        subscription.close();
        this.bindings.bind(() -> clock.onDirty(() -> {
        }));
        TickingTestSupport.advanceMillis(100);

        assertEquals(4L, clock.get());
    }

    @Test
    void aClockNobodyHoldsIsCollected() {
        WeakReference<?> probe = takeAndDrop(300);
        GcSupport.awaitCollected(probe);

        assertNotSame(probe.get(), Signals.everyMillis(300), "回收之后再要是一个新实例");
    }

    private static WeakReference<?> takeAndDrop(long periodMillis) {
        return new WeakReference<>(Signals.everyMillis(periodMillis));
    }

    @Test
    void periodsShorterThanOneTickAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Signals.everyMillis(49));
        assertThrows(IllegalArgumentException.class, () -> Signals.everyMillis(0));
        Signals.everyMillis(50);
    }
}
