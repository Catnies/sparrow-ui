package net.momirealms.sparrow.ui.internal;

import net.momirealms.sparrow.ui.ObservableDispatcher;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservableDispatcherTest {

    @Test
    void duplicateObserversHaveIndependentSubscriptions() {
        ObservableDispatcher<Integer> dispatcher = new ObservableDispatcher<>();
        List<Integer> updates = new ArrayList<>();
        Observer<Integer> observer = updates::add;
        Subscription first = dispatcher.subscribe(observer);
        Subscription second = dispatcher.subscribe(observer);
        first.close();
        first.close();
        dispatcher.publish(7);

        assertTrue(first.isClosed());
        assertFalse(second.isClosed());
        assertEquals(1, dispatcher.subscriptionCount());
        assertEquals(List.of(7), updates);
    }

    @Test
    void observerFailureDoesNotSkipRemainingObservers() {
        ObservableDispatcher<String> dispatcher = new ObservableDispatcher<>();
        IllegalStateException failure = new IllegalStateException("broken observer");
        List<String> updates = new ArrayList<>();
        dispatcher.subscribe(update -> {
            throw failure;
        });
        dispatcher.subscribe(updates::add);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> dispatcher.publish("update"));

        assertSame(failure, thrown);
        assertEquals(List.of("update"), updates);
    }
}
