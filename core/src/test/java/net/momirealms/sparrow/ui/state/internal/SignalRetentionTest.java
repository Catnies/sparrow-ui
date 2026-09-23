package net.momirealms.sparrow.ui.state.internal;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.keyed.KeyedSignalTestAccess;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalRetentionTest {

    private static final AtomicInteger STATIC_CALLS = new AtomicInteger();
    private static void staticCallback() {
        STATIC_CALLS.incrementAndGet();
    }

    @Test
    void droppingTheReceiptEndsEvenANonCapturingCallback() {
        MutableSignal<Integer> signal = Signal.of(0);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) signal;
        STATIC_CALLS.set(0);
        signal.onDirty(SignalRetentionTest::staticCallback);
        GcSupport.pressure();
        signal.set(1);

        assertEquals(0, STATIC_CALLS.get(), "凭证已丢弃, 常驻回调也不该再被调用");
        assertEquals(0, internal.entryCount());
    }

    @Test
    void closingADirectlyHeldReceiptReleasesCapturedObjects() {
        MutableSignal<Integer> signal = Signal.of(0);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) signal;
        WeakReference<?> probe = subscribeAndClose(signal);
        GcSupport.awaitCollected(probe);

        assertEquals(0, internal.entryCount());
    }

    private static WeakReference<?> subscribeAndClose(MutableSignal<Integer> signal) {
        Object captured = new Object();
        Subscription subscription = signal.onDirty(captured::hashCode);
        subscription.close();
        return new WeakReference<>(captured);
    }

    @Test
    void derivedNodeDoesNotLetUpstreamPinWhatTheMapperCaptured() {
        MutableSignal<Integer> global = Signal.of(0);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) global;
        AtomicInteger notifications = new AtomicInteger();
        WeakReference<?> probe = wireMapped(global, notifications);

        assertEquals(1, internal.entryCount(), "派生节点已挂到上游");
        GcSupport.awaitCollected(probe);
        global.set(1);

        assertEquals(0, internal.entryCount(), "持有方走后整条派生链应当解挂");
    }

    @Test
    void combinedNodeDoesNotLetSourcesPinWhatTheCombinerCaptured() {
        MutableSignal<Integer> left = Signal.of(0);
        MutableSignal<Integer> right = Signal.of(0);
        AbstractSignal<Integer> internalLeft = (AbstractSignal<Integer>) left;
        AbstractSignal<Integer> internalRight = (AbstractSignal<Integer>) right;
        AtomicInteger notifications = new AtomicInteger();
        WeakReference<?> probe = wireCombined(left, right, notifications);

        assertEquals(1, internalLeft.entryCount());
        GcSupport.awaitCollected(probe);
        left.set(1);

        assertEquals(0, internalLeft.entryCount(), "持有方走后组合节点应当从每个来源解挂");
        right.set(1);

        assertEquals(0, internalRight.entryCount());
    }

    @Test
    void mapDistinctDoesNotLetUpstreamPinWhatTheMapperCaptured() {
        MutableSignal<Long> tick = Signal.of(0L);
        AbstractSignal<Long> internal = (AbstractSignal<Long>) tick;
        AtomicInteger notifications = new AtomicInteger();
        WeakReference<?> probe = wireMapDistinct(tick, notifications);

        assertEquals(1, internal.entryCount());
        GcSupport.awaitCollected(probe);
        for (long i = 1; i <= 5; i++) {
            tick.set(i);
        }

        assertEquals(0, internal.entryCount(), "持有方走后降频节点应当解挂");
    }

    @Test
    void handleReturnedByBindDoesNotPinTheOwner() {
        MutableSignal<Integer> signal = Signal.of(0);
        AtomicInteger notifications = new AtomicInteger();
        Wiring wiring = bindDetachedOwner(signal, notifications);
        GcSupport.awaitCollected(wiring.probe());

        assertTrue(wiring.handle().isClosed(), "持有方已走, 句柄应当报告订阅已终止");
        wiring.handle().close();
        signal.set(1);

        assertEquals(0, notifications.get());
    }

    @Test
    void closingEarlyReleasesCapturedObjectsImmediately() {
        MutableSignal<Integer> signal = Signal.of(0);
        AtomicInteger notifications = new AtomicInteger();
        Bindings owner = new Bindings();
        Wiring wiring = bindCapturing(signal, owner, notifications);
        wiring.handle().close();
        GcSupport.awaitCollected(wiring.probe());
        signal.set(1);

        assertEquals(0, notifications.get());
        Reference.reachabilityFence(owner);
    }

    @Test
    void closingReleasesTheUpstreamChainNotJustTheCallback() {
        MutableSignal<Integer> global = Signal.of(0);
        AtomicInteger notifications = new AtomicInteger();
        Bindings owner = new Bindings();
        Wiring wiring = bindDerived(global, owner, notifications);
        wiring.handle().close();
        GcSupport.awaitCollected(wiring.probe());
        Reference.reachabilityFence(owner);
        Reference.reachabilityFence(global);
    }

    @Test
    void retiringTheSourceClosesTheDeclarationAndTheNextBindPrunesIt() {
        MutableKeyedSignal<String, Integer> keyed = KeyedSignal.of(key -> 0);
        AtomicInteger notifications = new AtomicInteger();
        Bindings owner = new Bindings();
        Wiring wiring = bindCapturing(KeyedSignalTestAccess.partition(keyed, "k"), owner, notifications);
        keyed.remove("k");

        assertTrue(wiring.handle().isClosed(), "来源终止后句柄应当报告订阅已终止");
        MutableSignal<Integer> other = Signal.of(0);
        owner.bind(() -> other.onDirty(notifications::incrementAndGet));
        GcSupport.awaitCollected(wiring.probe());
        Reference.reachabilityFence(owner);
        Reference.reachabilityFence(other);
    }

    private static Wiring bindDerived(MutableSignal<Integer> global, Bindings owner, AtomicInteger notifications) {
        Object captured = new Object();
        Signal<String> derived = global.map(value -> captured.toString() + value);
        Subscription handle = owner.bind(() -> derived.onDirty(notifications::incrementAndGet));
        return new Wiring(handle, new WeakReference<>(captured));
    }

    private static WeakReference<?> wireMapped(MutableSignal<Integer> global, AtomicInteger notifications) {
        Object captured = new Object();
        Signal<String> derived = global.map(value -> captured.toString() + value);
        Bindings owner = new Bindings();
        owner.bind(() -> derived.onDirty(notifications::incrementAndGet));
        return new WeakReference<>(captured);
    }

    private static WeakReference<?> wireCombined(MutableSignal<Integer> left, MutableSignal<Integer> right, AtomicInteger notifications) {
        Object captured = new Object();
        Signal<String> combined = Signals.combine(left, right, (a, b) -> captured.toString() + a + b);
        Bindings owner = new Bindings();
        owner.bind(() -> combined.onDirty(notifications::incrementAndGet));
        return new WeakReference<>(captured);
    }

    private static WeakReference<?> wireMapDistinct(MutableSignal<Long> tick, AtomicInteger notifications) {
        Object captured = new Object();
        Signal<Long> day = tick.mapDistinct(value -> value / 24000L + captured.hashCode());
        Bindings owner = new Bindings();
        owner.bind(() -> day.onDirty(notifications::incrementAndGet));
        return new WeakReference<>(captured);
    }

    private static Wiring bindDetachedOwner(Signal<?> signal, AtomicInteger notifications) {
        Owner owner = new Owner(notifications);
        return new Wiring(owner.bind(signal), new WeakReference<>(owner));
    }

    private static Wiring bindCapturing(Signal<?> signal, Bindings owner, AtomicInteger notifications) {
        Object captured = new Object();
        Subscription handle = owner.bind(() -> signal.onDirty(() -> {
            captured.hashCode();
            notifications.incrementAndGet();
        }));
        return new Wiring(handle, new WeakReference<>(captured));
    }

    private record Wiring(Subscription handle, WeakReference<?> probe) {
    }

    private static final class Owner {
        private final Bindings bindings = new Bindings();
        private final AtomicInteger notifications;
        private Owner(AtomicInteger notifications) {
            this.notifications = notifications;
        }
        Subscription bind(Signal<?> signal) {
            return this.bindings.bind(() -> signal.onDirty(() -> this.notifications.incrementAndGet()));
        }
    }
}
