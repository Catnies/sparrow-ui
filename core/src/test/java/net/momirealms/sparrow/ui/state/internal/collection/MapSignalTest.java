package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.state.MapSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.ExceptionHandlerProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapSignalTest {

    private final Bindings bindings = new Bindings();
    private MapSignal<String, Integer> abc() {
        Map<String, Integer> backing = new LinkedHashMap<>();
        backing.put("a", 1);
        backing.put("b", 2);
        backing.put("c", 3);
        return MapSignal.wrap(backing);
    }

    private AtomicInteger countNotifications(Signal<?> signal) {
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        return notifications;
    }

    static Stream<Arguments> mutationPaths() {
        return Stream.of(
                path("put new", m -> m.put("d", 4), Map.of("a", 1, "b", 2, "c", 3, "d", 4)),
                path("put replace", m -> m.put("a", 10), Map.of("a", 10, "b", 2, "c", 3)),
                path("putAll", m -> m.putAll(Map.of("d", 4, "e", 5)), Map.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5)),
                path("putIfAbsent", m -> m.putIfAbsent("d", 4), Map.of("a", 1, "b", 2, "c", 3, "d", 4)),
                path("replace", m -> m.replace("a", 10), Map.of("a", 10, "b", 2, "c", 3)),
                path("replace(k,old,new)", m -> m.replace("a", 1, 10), Map.of("a", 10, "b", 2, "c", 3)),
                path("replaceAll", m -> m.replaceAll((k, v) -> v * 10), Map.of("a", 10, "b", 20, "c", 30)),
                path("compute", m -> m.compute("a", (k, v) -> v + 1), Map.of("a", 2, "b", 2, "c", 3)),
                path("compute removes", m -> m.compute("a", (k, v) -> null), Map.of("b", 2, "c", 3)),
                path("computeIfAbsent", m -> m.computeIfAbsent("d", k -> 4), Map.of("a", 1, "b", 2, "c", 3, "d", 4)),
                path("computeIfPresent", m -> m.computeIfPresent("a", (k, v) -> v + 1), Map.of("a", 2, "b", 2, "c", 3)),
                path("merge", m -> m.merge("a", 5, Integer::sum), Map.of("a", 6, "b", 2, "c", 3)),
                path("merge new", m -> m.merge("d", 4, Integer::sum), Map.of("a", 1, "b", 2, "c", 3, "d", 4)),
                path("remove", m -> m.remove("a"), Map.of("b", 2, "c", 3)),
                path("remove(k,v)", m -> m.remove("a", 1), Map.of("b", 2, "c", 3)),
                path("clear", Map::clear, Map.of()),
                path("keySet.remove", m -> m.keySet().remove("a"), Map.of("b", 2, "c", 3)),
                path("keySet.removeAll", m -> m.keySet().removeAll(List.of("a", "b")), Map.of("c", 3)),
                path("keySet.retainAll", m -> m.keySet().retainAll(List.of("a")), Map.of("a", 1)),
                path("keySet.removeIf", m -> m.keySet().removeIf("b"::equals), Map.of("a", 1, "c", 3)),
                path("keySet.clear", m -> m.keySet().clear(), Map.of()),
                path("keySet.iterator.remove", m -> {
                    Iterator<String> it = m.keySet().iterator();
                    it.next();
                    it.remove();
                }, Map.of("b", 2, "c", 3)),
                path("values.remove", m -> m.values().remove(2), Map.of("a", 1, "c", 3)),
                path("values.removeIf", m -> m.values().removeIf(v -> v > 1), Map.of("a", 1)),
                path("values.retainAll", m -> m.values().retainAll(List.of(1)), Map.of("a", 1)),
                path("values.iterator.remove", m -> {
                    Iterator<Integer> it = m.values().iterator();
                    it.next();
                    it.next();
                    it.remove();
                }, Map.of("a", 1, "c", 3)),
                path("entrySet.remove", m -> m.entrySet().remove(Map.entry("a", 1)), Map.of("b", 2, "c", 3)),
                path("entrySet.removeIf", m -> m.entrySet().removeIf(e -> e.getValue() == 3), Map.of("a", 1, "b", 2)),
                path("entrySet.iterator.remove", m -> {
                    Iterator<Map.Entry<String, Integer>> it = m.entrySet().iterator();
                    it.next();
                    it.remove();
                }, Map.of("b", 2, "c", 3)),
                path("Entry.setValue", m -> m.entrySet().iterator().next().setValue(100), Map.of("a", 100, "b", 2, "c", 3)),
                path("entrySet.stream entry setValue", m -> m.entrySet().stream().filter(e -> e.getKey().equals("b")).findFirst().orElseThrow().setValue(20), Map.of("a", 1, "b", 20, "c", 3)),
                path("entrySet.toArray entry setValue", MapSignalTest::setThirdViaToArray, Map.of("a", 1, "b", 2, "c", 30))
        );
    }

    @SuppressWarnings("unchecked")
    private static void setThirdViaToArray(MapSignal<String, Integer> m) {
        ((Map.Entry<String, Integer>) m.entrySet().toArray()[2]).setValue(30);
    }

    private static Arguments path(String name, Consumer<MapSignal<String, Integer>> action, Map<String, Integer> expected) {
        return Arguments.of(name, action, expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutationPaths")
    void everyMutationPathNotifiesExactlyOnce(String name, Consumer<MapSignal<String, Integer>> action, Map<String, Integer> expected) {
        MapSignal<String, Integer> signal = this.abc();
        AtomicInteger notifications = this.countNotifications(signal);
        action.accept(signal);

        assertEquals(expected, Map.copyOf(signal), name + " 的终态");
        assertEquals(1, notifications.get(), name + " 应恰好通知一次");
    }

    @Test
    void ineffectiveChangesDoNotNotify() {
        MapSignal<String, Integer> signal = this.abc();
        AtomicInteger notifications = this.countNotifications(signal);
        signal.put("a", 1);
        signal.putAll(Map.of());
        signal.putIfAbsent("a", 99);
        signal.replace("x", 1);
        signal.replace("a", 1, 1);
        signal.replace("a", 5, 6);
        signal.replaceAll((k, v) -> v);
        signal.compute("a", (k, v) -> v);
        signal.computeIfAbsent("a", k -> 99);
        signal.computeIfPresent("x", (k, v) -> 1);
        signal.remove("x");
        signal.remove("a", 99);
        signal.keySet().remove("x");
        signal.values().remove(99);
        signal.entrySet().remove(Map.entry("a", 99));
        signal.entrySet().iterator().next().setValue(1);
        MapSignal.of().clear();

        assertEquals(0, notifications.get());
        assertEquals(Map.of("a", 1, "b", 2, "c", 3), Map.copyOf(signal));
    }

    @Test
    void viewsReadThroughAndEntriesWriteThrough() {
        MapSignal<String, Integer> signal = this.abc();

        assertEquals(List.of("a", "b", "c"), List.copyOf(signal.keySet()));
        assertEquals(List.of(1, 2, 3), List.copyOf(signal.values()));
        assertTrue(signal.entrySet().contains(Map.entry("a", 1)));
        assertTrue(signal.keySet().contains("a"));
        assertEquals(3, signal.entrySet().size());
        assertEquals(signal.keySet(), Map.of("a", 1, "b", 2, "c", 3).keySet(), "视图按内容判等");
        signal.entrySet().forEach(entry -> entry.setValue(entry.getValue() * 10));

        assertEquals(Map.of("a", 10, "b", 20, "c", 30), Map.copyOf(signal));
    }

    @Test
    void perKeyObservationThroughMapDistinct() {
        MapSignal<String, Integer> signal = MapSignal.of();
        Signal<Integer> a = signal.mapDistinct(m -> m.get("a"));
        AtomicInteger notifications = this.countNotifications(a);
        signal.put("b", 1);
        signal.put("c", 2);

        assertEquals(0, notifications.get(), "别的 key 变化被判等截断");
        signal.put("a", 1);

        assertEquals(1, notifications.get());
        signal.put("a", 1);

        assertEquals(1, notifications.get(), "相同值不通知");
        signal.put("a", 2);

        assertEquals(2, notifications.get());
        assertEquals(2, a.get());
    }

    @Test
    void equalityIsByIdentityAndGetReturnsItself() {
        MapSignal<String, Integer> signal = this.abc();

        assertNotEquals(signal, Map.of("a", 1, "b", 2, "c", 3));
        assertTrue(Map.of("a", 1, "b", 2, "c", 3).equals(signal), "普通 Map 按内容看它");
        assertSame(signal, signal.get());
    }

    @Test
    void concurrentMergesOnAConcurrentDelegateStayAtomic() throws InterruptedException {
        MapSignal<String, Integer> signal = MapSignal.wrap(new ConcurrentHashMap<>());
        AtomicInteger notifications = this.countNotifications(signal);
        int writers = 4;
        int perWriter = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            for (int w = 0; w < writers; w++) {
                Thread writer = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perWriter; i++) {
                        signal.merge("counter", 1, Integer::sum);
                    }
                    done.countDown();
                });
                writer.start();
            }
            start.countDown();

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(writers * perWriter, signal.get("counter"), "merge 经 delegate 的原子版本, 计数不丢");
            assertEquals(writers * perWriter, notifications.get());
            assertEquals(List.of(), probe.failures());
        }
    }
}
