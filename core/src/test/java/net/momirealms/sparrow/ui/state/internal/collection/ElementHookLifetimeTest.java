package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MapSignal;
import net.momirealms.sparrow.ui.state.MutableListSignal;
import net.momirealms.sparrow.ui.state.MutableMapSignal;
import net.momirealms.sparrow.ui.state.MutableSetSignal;
import net.momirealms.sparrow.ui.state.SetSignal;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementHookLifetimeTest {

    @Test
    void hookRunsWhileTheTokenIsHeld() {
        List<String> seen = new ArrayList<>();
        MutableListSignal<String> signal = ListSignal.of();
        Subscription token = signal.beforeAdd(element -> {
            seen.add(element);
            return element;
        });
        signal.add("a");

        assertEquals(List.of("a"), seen);
        assertFalse(token.isClosed());
    }

    @Test
    void closingTheTokenDetachesTheHook() {
        List<String> seen = new ArrayList<>();
        MutableListSignal<String> signal = ListSignal.of();
        Subscription token = signal.beforeAdd(element -> {
            seen.add(element);
            return "<" + element + ">";
        });
        signal.add("a");
        token.close();
        signal.add("b");

        assertTrue(token.isClosed());
        assertEquals(List.of("a"), seen, "关掉之后钩子不该再被调用");
        assertEquals(List.of("<a>", "b"), List.copyOf(signal), "关掉之后元素原样存入");
    }

    @Test
    void closingIsIdempotent() {
        MutableListSignal<String> signal = ListSignal.of();
        Subscription token = signal.beforeAdd(Function.identity());
        token.close();
        token.close();

        assertTrue(token.isClosed());
    }

    @Test
    void closingOneTokenLeavesTheOthersInOrder() {
        MutableListSignal<String> signal = ListSignal.of();
        Subscription first = signal.beforeAdd(element -> element + "1");
        Subscription second = signal.beforeAdd(element -> element + "2");
        Subscription third = signal.beforeAdd(element -> element + "3");
        signal.add("a");
        second.close();
        signal.add("b");

        assertEquals(List.of("a123", "b13"), List.copyOf(signal));
        assertFalse(first.isClosed());
        assertFalse(third.isClosed());
    }

    @Test
    void anUnheldHookDiesWithItsToken() {
        List<String> seen = new ArrayList<>();
        MutableListSignal<String> signal = ListSignal.of();
        WeakReference<Subscription> probe = new WeakReference<>(register(signal, seen));
        GcSupport.awaitCollected(probe);
        signal.add("a");

        assertEquals(List.of(), seen, "凭证被回收后钩子不该再被调用");
        assertEquals(List.of("a"), List.copyOf(signal));
    }

    private static Subscription register(MutableListSignal<String> signal, List<String> seen) {
        return signal.beforeAdd(element -> {
            seen.add(element);
            return element;
        });
    }

    @Test
    void aHeldHookSurvivesCollectionPressure() {
        List<String> seen = new ArrayList<>();
        MutableListSignal<String> signal = ListSignal.of();
        Subscription token = signal.beforeAdd(element -> {
            seen.add(element);
            return element;
        });
        GcSupport.pressure();
        signal.add("a");

        assertEquals(List.of("a"), seen);
        assertFalse(token.isClosed());
    }

    @Test
    void setAndMapTokensBehaveTheSameWay() {
        List<String> seen = new ArrayList<>();
        MutableSetSignal<String> set = SetSignal.wrap(new LinkedHashSet<>());
        Subscription setToken = set.beforeAdd(element -> {
            seen.add("set:" + element);
            return element;
        });
        MutableMapSignal<String, String> map = MapSignal.wrap(new LinkedHashMap<>());
        Subscription mapToken = map.beforePut((key, value) -> {
            seen.add("map:" + key);
            return value;
        });
        set.add("a");
        map.put("k", "v");
        setToken.close();
        mapToken.close();
        set.add("b");
        map.put("k2", "v2");

        assertEquals(List.of("set:a", "map:k"), seen);
    }
}
