package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.MapSignal;
import net.momirealms.sparrow.ui.state.MutableMapSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class MapSignalNoOpMutationRegressionTest {

    private final java.util.List<Subscription> hookTokens = new java.util.ArrayList<>();
    private final Bindings bindings = new Bindings();

    @Test
    void computeIfAbsentReturningNullFromHookDoesNotAdvanceOrNotify() {
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(new LinkedHashMap<String, Integer>());
        this.hookTokens.add(signal.beforePut((key, value) -> null));
        CollectionSignal<?> internal = (CollectionSignal<?>) signal;
        long version = internal.version();
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));

        assertNull(signal.computeIfAbsent("a", key -> 1));
        assertFalse(signal.containsKey("a"));
        assertEquals(version, internal.version());
        assertEquals(0, notifications.get());
    }

    @Test
    void detachedEntrySetValueDoesNotRunHooksAdvanceOrNotify() {
        Map<String, Integer> delegate = new LinkedHashMap<>();
        delegate.put("a", 1);
        List<String> hooks = new ArrayList<>();
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(delegate);
        this.hookTokens.add(signal.beforePut((key, value) -> {
            hooks.add("put:" + key + "=" + value);
            return value;
        }));
        this.hookTokens.add(signal.afterRemove((key, value) -> hooks.add("remove:" + key + "=" + value)));
        Map.Entry<String, Integer> entry = signal.entrySet().iterator().next();
        signal.remove("a");
        hooks.clear();
        CollectionSignal<?> internal = (CollectionSignal<?>) signal;
        long version = internal.version();
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));

        assertEquals(1, entry.setValue(2));
        assertFalse(signal.containsKey("a"));
        assertEquals(List.of(), hooks);
        assertEquals(version, internal.version());
        assertEquals(0, notifications.get());
    }
}
