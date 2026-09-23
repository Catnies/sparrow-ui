package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.MapSignal;
import net.momirealms.sparrow.ui.state.MutableMapSignal;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class MapSignalNullRemoveRegressionTest {

    private final java.util.List<Subscription> hookTokens = new java.util.ArrayList<>();
    private final Bindings bindings = new Bindings();

    @Test
    void removeRunsHookAndNotifiesForNullValueMapping() {
        Map<String, Integer> delegate = new HashMap<>();
        delegate.put("a", null);
        AtomicInteger hookCalls = new AtomicInteger();
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(delegate);
        this.hookTokens.add(signal.afterRemove((key, value) -> {
            assertEquals("a", key);
            assertNull(value);
            hookCalls.incrementAndGet();
        }));
        CollectionSignal<?> internal = (CollectionSignal<?>) signal;
        long version = internal.version();
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));

        assertNull(signal.remove("a"));
        assertFalse(signal.containsKey("a"));
        assertEquals(1, hookCalls.get());
        assertEquals(version + 1, internal.version());
        assertEquals(1, notifications.get());
    }
}
