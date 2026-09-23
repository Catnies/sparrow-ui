package net.momirealms.sparrow.ui.state.internal.collection;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapSignalNullValueRemovalTest {

    private final java.util.List<Subscription> hookTokens = new java.util.ArrayList<>();

    @Test
    void conditionalRemoveHandlesNullValueAfterHookRegistration() {
        Map<String, Integer> delegate = new HashMap<>();
        delegate.put("a", null);
        AtomicInteger hookCalls = new AtomicInteger();
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(delegate);
        this.hookTokens.add(signal.afterRemove((key, value) -> {
            assertEquals("a", key);
            assertNull(value);
            hookCalls.incrementAndGet();
        }));

        assertTrue(signal.remove("a", null));
        assertFalse(signal.containsKey("a"));
        assertEquals(1, hookCalls.get());
    }

    @Test
    void replaceNotifiesWhenSwappingOutANullValueMapping() {
        Map<String, Integer> delegate = new HashMap<>();
        delegate.put("a", null);
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(delegate);
        AtomicInteger dirties = new AtomicInteger();
        Subscription subscription = signal.onDirty(dirties::incrementAndGet);

        assertNull(signal.replace("a", 42));
        assertEquals(42, delegate.get("a"));
        assertEquals(1, dirties.get(), "null -> 42 是实际变更, 必须通知");
        subscription.close();
    }

    @Test
    void hookedReplaceSwapsOutANullValueMapping() {
        Map<String, Integer> delegate = new HashMap<>();
        delegate.put("a", null);
        AtomicInteger removals = new AtomicInteger();
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(delegate);
        this.hookTokens.add(signal.afterRemove((key, value) -> removals.incrementAndGet()));
        AtomicInteger dirties = new AtomicInteger();
        Subscription subscription = signal.onDirty(dirties::incrementAndGet);

        assertNull(signal.replace("a", 42));
        assertEquals(42, delegate.get("a"), "挂了钩子的 replace 也要真的替换");
        assertEquals(1, dirties.get());
        assertEquals(0, removals.get(), "null 旧值被覆盖时与 put 一样不过移除钩子");
        subscription.close();
    }

    @Test
    void hookedThreeArgReplaceMatchesANullOldValue() {
        Map<String, Integer> delegate = new HashMap<>();
        delegate.put("a", null);
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(delegate);
        this.hookTokens.add(signal.afterRemove((key, value) -> {}));

        assertTrue(signal.replace("a", null, 42), "null 旧值按 Map 契约参与匹配");
        assertEquals(42, delegate.get("a"));
    }

    @Test
    void removeRaceLoserDoesNotFabricateAHookCall() {
        Map<String, Integer> delegate = new HashMap<>() {
            @Override
            public Integer remove(Object key) {
                super.remove(key);
                return null;
            }
        };
        delegate.put("a", 5);
        AtomicInteger hookCalls = new AtomicInteger();
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(delegate);
        this.hookTokens.add(signal.afterRemove((key, value) -> hookCalls.incrementAndGet()));
        AtomicInteger dirties = new AtomicInteger();
        Subscription subscription = signal.onDirty(dirties::incrementAndGet);

        assertNull(signal.remove("a"));
        assertEquals(0, hookCalls.get(), "没删到就不能伪造一次移除");
        assertEquals(0, dirties.get());
        subscription.close();
    }

    @Test
    void keySetRemoveRaceLoserReportsFalseWithoutHooks() {
        Map<String, Integer> delegate = new HashMap<>() {
            @Override
            public Integer remove(Object key) {
                super.remove(key);
                return null;
            }
        };
        delegate.put("a", 5);
        AtomicInteger hookCalls = new AtomicInteger();
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(delegate);
        this.hookTokens.add(signal.afterRemove((key, value) -> hookCalls.incrementAndGet()));

        assertFalse(signal.keySet().remove("a"), "没删到不能谎报删除");
        assertEquals(0, hookCalls.get());
    }

    @Test
    void entryRemovalWithAllRacesLostDoesNotNotify() {
        Map<String, Integer> delegate = new HashMap<>() {
            @Override
            public boolean remove(Object key, Object value) {
                return false;
            }
        };
        delegate.put("a", 5);
        MutableMapSignal<String, Integer> signal = MapSignal.wrap(delegate);
        this.hookTokens.add(signal.afterRemove((key, value) -> {}));
        AtomicInteger dirties = new AtomicInteger();
        Subscription subscription = signal.onDirty(dirties::incrementAndGet);

        assertFalse(signal.entrySet().removeIf(entry -> true), "一条都没删掉就不算删除");
        assertEquals(0, dirties.get());
        subscription.close();
    }
}
