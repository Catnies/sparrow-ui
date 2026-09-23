package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MapSignal;
import net.momirealms.sparrow.ui.state.MutableListSignal;
import net.momirealms.sparrow.ui.state.MutableMapSignal;
import net.momirealms.sparrow.ui.state.Signal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionSignalNoOpConsistencyRegressionTest {

    private final java.util.List<Subscription> hookTokens = new java.util.ArrayList<>();
    private final Bindings bindings = new Bindings();

    @Test
    void listReplaceAllIdentityWithoutHooksDoesNotAdvanceOrNotify() {
        this.assertIdentityReplacementIsQuiet(ListSignal.wrap(new ArrayList<>(List.of("a", "b"))));
    }

    @Test
    void listReplaceAllIdentityWithHooksDoesNotAdvanceOrNotify() {
        MutableListSignal<String> signal = ListSignal.wrap(new ArrayList<>(List.of("a", "b")));
        this.hookTokens.add(signal.beforeAdd(UnaryOperator.identity()));
        this.hookTokens.add(signal.afterRemove(ignored -> {
        }));
        this.assertIdentityReplacementIsQuiet(signal);
    }

    @Test
    void sortWithTwoElementsUsesConservativeNotification() {
        MutableListSignal<String> signal = ListSignal.wrap(new ArrayList<>(List.of("a", "b")));
        AtomicInteger notifications = this.countNotifications(signal);
        signal.sort(null);

        assertEquals(1, notifications.get());
    }

    @Test
    void unhookedNonEmptyPutAllUsesConservativeNotification() {
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(new LinkedHashMap<>(Map.of("a", 1)));
        AtomicInteger notifications = this.countNotifications(signal);
        signal.putAll(Map.of("a", 1));

        assertEquals(1, notifications.get());
    }

    private void assertIdentityReplacementIsQuiet(MutableListSignal<String> signal) {
        CollectionSignal<?> internal = (CollectionSignal<?>) signal;
        long version = internal.version();
        AtomicInteger notifications = this.countNotifications(signal);
        signal.replaceAll(UnaryOperator.identity());

        assertEquals(version, internal.version());
        assertEquals(0, notifications.get());
    }

    private AtomicInteger countNotifications(Signal<?> signal) {
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        return notifications;
    }
}
