package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.Bindings;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MapSignalPutIdentityRegressionTest {

    private final java.util.List<Subscription> hookTokens = new java.util.ArrayList<>();
    private final Bindings bindings = new Bindings();

    @Test
    void equalPutRetainsTheStoredInstanceWithAndWithoutHooks() {
        this.assertEqualPutRetainsStoredInstance(MapSignal.wrap(new LinkedHashMap<>()));
        MapSignal<String, Foo> hooked = MapSignal.wrap(new LinkedHashMap<>());
        this.hookTokens.add(hooked.beforePut((key, value) -> value));
        this.assertEqualPutRetainsStoredInstance(hooked);
    }

    private void assertEqualPutRetainsStoredInstance(MapSignal<String, Foo> signal) {
        Foo first = new Foo(1);
        Foo equal = new Foo(1);
        signal.put("k", first);
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));

        assertSame(first, signal.put("k", equal));
        assertSame(first, signal.get("k"));
        assertEquals(0, notifications.get());
    }

    private record Foo(int value) {
    }
}
