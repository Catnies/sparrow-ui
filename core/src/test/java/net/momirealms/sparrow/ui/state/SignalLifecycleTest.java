package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignalLifecycleTest {

    @Test
    void halfDoneAttachRollsBackWhatItAlreadyAttached() {
        MutableSignal<Integer> healthy = Signal.of(0);
        Signal<Integer> exploding = Signal.of(0).mapDistinct(value -> {
            throw new IllegalStateException("baseline mapper exploded");
        });
        Signal<Integer> combined = Signals.combine(healthy, exploding, (left, ignoredRight) -> left);

        assertThrows(IllegalStateException.class, () -> combined.onDirty(() -> {
        }));

        assertEquals(0, ((AbstractSignal<?>) healthy).entryCount());
    }

    @Test
    void derivedSignalAttachesUpstreamOnlyWhileSubscribed() {
        MutableSignal<Integer> source = Signal.of(1);
        AbstractSignal<Integer> internalSource = (AbstractSignal<Integer>) source;
        Signal<Integer> mapped = source.map(value -> value * 2);

        assertEquals(0, internalSource.entryCount());
        Subscription subscription = mapped.onDirty(() -> {
        });

        assertEquals(1, internalSource.entryCount());
        subscription.close();

        assertEquals(0, internalSource.entryCount());
    }

    @Test
    void activationCascadesThroughDerivationChain() {
        MutableSignal<Integer> source = Signal.of(1);
        AbstractSignal<Integer> internalSource = (AbstractSignal<Integer>) source;
        Signal<Integer> first = source.map(value -> value + 1);
        Signal<Integer> second = first.map(value -> value * 2);
        AbstractSignal<Integer> internalFirst = (AbstractSignal<Integer>) first;
        Subscription subscription = second.onDirty(() -> {
        });

        assertEquals(1, internalSource.entryCount());
        assertEquals(1, internalFirst.entryCount());
        subscription.close();

        assertEquals(0, internalSource.entryCount());
        assertEquals(0, internalFirst.entryCount());
    }

    @Test
    void multipleSubscribersShareOneUpstreamAttachment() {
        MutableSignal<Integer> source = Signal.of(1);
        AbstractSignal<Integer> internalSource = (AbstractSignal<Integer>) source;
        Signal<Integer> mapped = source.map(value -> value * 2);
        Subscription first = mapped.onDirty(() -> {
        });
        Subscription second = mapped.onDirty(() -> {
        });

        assertEquals(1, internalSource.entryCount());
        first.close();

        assertEquals(1, internalSource.entryCount());
        second.close();

        assertEquals(0, internalSource.entryCount());
    }

    @Test
    void combinedSignalAttachesAndDetachesAllSources() {
        MutableSignal<Integer> left = Signal.of(1);
        MutableSignal<Integer> right = Signal.of(2);
        AbstractSignal<Integer> internalLeft = (AbstractSignal<Integer>) left;
        AbstractSignal<Integer> internalRight = (AbstractSignal<Integer>) right;
        Signal<Integer> sum = Signals.combine(left, right, Integer::sum);
        Subscription subscription = sum.onDirty(() -> {
        });

        assertEquals(1, internalLeft.entryCount());
        assertEquals(1, internalRight.entryCount());
        subscription.close();

        assertEquals(0, internalLeft.entryCount());
        assertEquals(0, internalRight.entryCount());
    }

    @Test
    void failedActivationLeavesNoResidualSubscription() {
        MutableSignal<Integer> source = Signal.of(1);
        AbstractSignal<Integer> internalSource = (AbstractSignal<Integer>) source;
        IllegalStateException failure = new IllegalStateException("broken mapper");
        Signal<Integer> broken = source.mapDistinct(value -> {
            throw failure;
        });

        assertSame(failure, assertThrows(RuntimeException.class, () -> broken.onDirty(() -> {
        })));

        assertEquals(0, internalSource.entryCount());
        assertEquals(0, ((AbstractSignal<Integer>) broken).entryCount());
    }

    @Test
    void weakSubscriptionDeliversWhileTheReceiptIsHeld() {
        MutableSignal<Integer> signal = Signal.of(0);
        List<Integer> pulledValues = new ArrayList<>();
        Subscription subscription = signal.onDirty(() -> pulledValues.add(signal.get()));
        signal.set(5);

        assertEquals(List.of(5), pulledValues);
        Reference.reachabilityFence(subscription);
    }

    @Test
    void weakSubscriptionDroppedAfterOwnerCollected() {
        MutableSignal<Integer> signal = Signal.of(0);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) signal;
        List<Integer> received = new ArrayList<>();
        Bindings owner = new Bindings();
        WeakReference<Bindings> probe = new WeakReference<>(owner);
        owner.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        signal.set(1);

        assertEquals(List.of(1), received);
        owner = null;
        GcSupport.awaitCollected(probe);
        signal.set(2);

        assertEquals(List.of(1), received);
        assertEquals(0, internal.entryCount());
    }

    @Test
    void callbackMayCaptureItsOwnerWithoutPinningIt() {
        MutableSignal<Integer> signal = Signal.of(0);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) signal;
        List<Integer> received = new ArrayList<>();
        CapturingOwner owner = new CapturingOwner(received);
        owner.bind(signal);
        WeakReference<CapturingOwner> probe = new WeakReference<>(owner);
        signal.set(1);

        assertEquals(List.of(1), received);
        owner = null;
        GcSupport.awaitCollected(probe);
        signal.set(2);

        assertEquals(List.of(1), received, "持有方回收后不应再送达");
        assertEquals(0, internal.entryCount());
    }

    @Test
    void deadWeakEntryRemovalDeactivatesDerivedChain() {
        MutableSignal<Integer> source = Signal.of(0);
        AbstractSignal<Integer> internalSource = (AbstractSignal<Integer>) source;
        Signal<Integer> mapped = source.map(value -> value * 2);
        AtomicInteger notifications = new AtomicInteger();
        Bindings owner = new Bindings();
        WeakReference<Bindings> probe = new WeakReference<>(owner);
        owner.bind(() -> mapped.onDirty(notifications::incrementAndGet));

        assertEquals(1, internalSource.entryCount());
        owner = null;
        GcSupport.awaitCollected(probe);
        source.set(1);

        assertEquals(0, internalSource.entryCount());
    }

    @Test
    void closingWeakSubscriptionEarlyRemovesEntryImmediately() {
        MutableSignal<Integer> signal = Signal.of(0);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) signal;
        AtomicInteger notifications = new AtomicInteger();
        Subscription subscription = signal.onDirty(notifications::incrementAndGet);

        assertEquals(1, internal.entryCount());
        subscription.close();

        assertEquals(0, internal.entryCount());
    }

    @Test
    void signalDoesNotPreventSubscriberChainFromBeingCollected() {
        MutableSignal<Integer> longLived = Signal.of(0);
        AtomicInteger notifications = new AtomicInteger();
        Bindings shortLived = new Bindings();
        WeakReference<Bindings> probe = new WeakReference<>(shortLived);
        shortLived.bind(() -> longLived.onDirty(notifications::incrementAndGet));
        shortLived = null;
        GcSupport.awaitCollected(probe);
    }

    private static final class CapturingOwner {
        private final Bindings bindings = new Bindings();
        private final List<Integer> received;
        private CapturingOwner(List<Integer> received) {
            this.received = received;
        }
        void bind(Signal<Integer> signal) {
            this.bindings.bind(() -> signal.onDirty(() -> this.received.add(signal.get())));
        }
    }
}
