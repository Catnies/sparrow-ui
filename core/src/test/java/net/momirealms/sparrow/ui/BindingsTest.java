package net.momirealms.sparrow.ui;

import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.TickingTestSupport;
import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingsTest {

    @Test
    void suspendDetachesAndResumeReattaches() {
        MutableSignal<Integer> signal = Signal.of(0);
        Bindings bindings = new Bindings();
        AtomicInteger notifications = new AtomicInteger();
        Subscription handle = bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));

        assertEquals(1, TickingTestSupport.entryCountOf(signal));
        bindings.suspendAll();

        assertEquals(0, TickingTestSupport.entryCountOf(signal), "挂起后不该继续占着上游的订阅表");
        signal.set(1);

        assertEquals(0, notifications.get(), "挂起期间不回调");
        assertFalse(handle.isClosed(), "声明还在, 只是挂起");
        bindings.resumeAll();
        signal.set(2);

        assertEquals(1, TickingTestSupport.entryCountOf(signal));
        assertEquals(1, notifications.get());
        Reference.reachabilityFence(bindings);
    }

    @Test
    void bindWhileSuspendedOnlyAttachesOnResume() {
        MutableSignal<Integer> signal = Signal.of(0);
        Bindings bindings = new Bindings();
        AtomicInteger notifications = new AtomicInteger();
        bindings.suspendAll();
        bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));

        assertEquals(0, TickingTestSupport.entryCountOf(signal), "挂起期间登记的声明先不挂上");
        signal.set(1);

        assertEquals(0, notifications.get());
        bindings.resumeAll();
        signal.set(2);

        assertEquals(1, notifications.get());
        Reference.reachabilityFence(bindings);
    }

    @Test
    void closedBindingDoesNotComeBackOnResume() {
        MutableSignal<Integer> signal = Signal.of(0);
        Bindings bindings = new Bindings();
        AtomicInteger notifications = new AtomicInteger();
        Subscription handle = bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        bindings.suspendAll();
        handle.close();
        bindings.resumeAll();
        signal.set(1);

        assertTrue(handle.isClosed());
        assertEquals(0, TickingTestSupport.entryCountOf(signal), "关掉的声明不该被恢复");
        assertEquals(0, notifications.get());
        Reference.reachabilityFence(bindings);
    }

    @Test
    void failedSubscribeLeavesNoDeclarationBehind() {
        MutableSignal<Integer> signal = Signal.of(0);
        Bindings bindings = new Bindings();
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> bindings.bind(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        bindings.suspendAll();
        bindings.resumeAll();

        assertEquals(1, attempts.get());
        assertEquals(0, TickingTestSupport.entryCountOf(signal));
        Reference.reachabilityFence(bindings);
    }
}
