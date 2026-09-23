package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.MapSignal;
import net.momirealms.sparrow.ui.state.MutableMapSignal;
import net.momirealms.sparrow.ui.state.MutableSetSignal;
import net.momirealms.sparrow.ui.state.SetSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadOnlyCollectionSignalsTest {

    static Stream<Arguments> setMutations() {
        return Stream.of(
                setMutation("add", set -> set.add("c")),
                setMutation("addAll", set -> set.addAll(Set.of("c"))),
                setMutation("remove", set -> set.remove("a")),
                setMutation("removeAll", set -> set.removeAll(Set.of("a"))),
                setMutation("retainAll", set -> set.retainAll(Set.of("a"))),
                setMutation("removeIf", set -> set.removeIf(value -> true)),
                setMutation("clear", Set::clear),
                setMutation("iterator", set -> {
                    var iterator = set.iterator();
                    iterator.next();
                    iterator.remove();
                }),
                setMutation("existing add", set -> set.add("a")),
                setMutation("empty addAll", set -> set.addAll(Set.of())),
                setMutation("absent remove", set -> set.remove("missing")),
                setMutation("no-op removeIf", set -> set.removeIf(value -> false))
        );
    }

    private static Arguments setMutation(String name, Consumer<Set<String>> action) {
        return Arguments.of(name, action);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("setMutations")
    void setRejectsEveryMutation(String name, Consumer<Set<String>> action) {
        MutableSetSignal<String> source = SetSignal.wrap(new HashSet<>(Set.of("a", "b")));
        SetSignal<String> view = source.asReadOnly();
        AtomicInteger notifications = new AtomicInteger();
        try (Subscription ignored = source.onDirty(notifications::incrementAndGet)) {
            assertThrows(UnsupportedOperationException.class, () -> action.accept(view));
            assertThrows(UnsupportedOperationException.class, () -> action.accept(view.get()));
            assertEquals(Set.of("a", "b"), source);
            assertEquals(0, notifications.get());
        }
    }

    static Stream<Arguments> mapMutations() {
        return Stream.of(
                mapMutation("put", map -> map.put("c", 3)),
                mapMutation("putAll", map -> map.putAll(Map.of("c", 3))),
                mapMutation("putIfAbsent", map -> map.putIfAbsent("c", 3)),
                mapMutation("replace", map -> map.replace("a", 3)),
                mapMutation("conditional replace", map -> map.replace("a", 1, 3)),
                mapMutation("replaceAll", map -> map.replaceAll((key, value) -> value + 1)),
                mapMutation("compute", map -> map.compute("a", (key, value) -> 3)),
                mapMutation("computeIfAbsent", map -> map.computeIfAbsent("c", key -> 3)),
                mapMutation("computeIfPresent", map -> map.computeIfPresent("a", (key, value) -> 3)),
                mapMutation("merge", map -> map.merge("a", 3, Integer::sum)),
                mapMutation("remove", map -> map.remove("a")),
                mapMutation("conditional remove", map -> map.remove("a", 1)),
                mapMutation("clear", Map::clear),
                mapMutation("keySet", map -> map.keySet().remove("a")),
                mapMutation("keySet iterator", map -> {
                    var iterator = map.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }),
                mapMutation("values", map -> map.values().remove(1)),
                mapMutation("values iterator", map -> {
                    var iterator = map.values().iterator();
                    iterator.next();
                    iterator.remove();
                }),
                mapMutation("entrySet remove", map -> map.entrySet().remove(Map.entry("a", 1))),
                mapMutation("entrySet clear", map -> map.entrySet().clear()),
                mapMutation("entrySet iterator", map -> {
                    var iterator = map.entrySet().iterator();
                    iterator.next();
                    iterator.remove();
                }),
                mapMutation("entrySet removeIf", map -> map.entrySet().removeIf(entry -> true)),
                mapMutation("entrySet forEach", map -> map.entrySet().forEach(entry -> entry.setValue(3))),
                mapMutation("entrySet spliterator", map -> map.entrySet().spliterator().tryAdvance(entry -> entry.setValue(3))),
                mapMutation("entrySet parallelStream", map -> map.entrySet().parallelStream().findFirst().orElseThrow().setValue(3)),
                mapMutation("empty putAll", map -> map.putAll(Map.of())),
                mapMutation("existing putIfAbsent", map -> map.putIfAbsent("a", 3)),
                mapMutation("absent remove", map -> map.remove("missing"))
        );
    }

    private static Arguments mapMutation(String name, Consumer<Map<String, Integer>> action) {
        return Arguments.of(name, action);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mapMutations")
    void mapAndItsViewsRejectEveryMutation(String name, Consumer<Map<String, Integer>> action) {
        MutableMapSignal<String, Integer> source = MapSignal.wrap(new HashMap<>(Map.of("a", 1, "b", 2)));
        MapSignal<String, Integer> view = source.asReadOnly();
        AtomicInteger notifications = new AtomicInteger();
        try (Subscription ignored = source.onDirty(notifications::incrementAndGet)) {
            assertThrows(UnsupportedOperationException.class, () -> action.accept(view));
            assertThrows(UnsupportedOperationException.class, () -> action.accept(view.get()));
            assertEquals(Map.of("a", 1, "b", 2), source);
            assertEquals(0, notifications.get());
        }
    }

    static Stream<Arguments> entryAccessors() {
        return Stream.of(
                entryAccessor("iterator", entries -> entries.iterator().next()),
                entryAccessor("stream", entries -> entries.stream().findFirst().orElseThrow()),
                entryAccessor("array", entries -> castEntry(entries.toArray()[0])),
                entryAccessor("typed array", entries -> castEntry(entries.toArray(new Map.Entry<?, ?>[0])[0])),
                entryAccessor("array generator", entries -> castEntry(entries.toArray(Map.Entry<?, ?>[]::new)[0]))
        );
    }

    private static Arguments entryAccessor(String name, Function<Set<Map.Entry<String, Integer>>, Map.Entry<String, Integer>> accessor) {
        return Arguments.of(name, accessor);
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<String, Integer> castEntry(Object entry) {
        return (Map.Entry<String, Integer>) entry;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryAccessors")
    void extractedEntriesRejectSetValue(String name, Function<Set<Map.Entry<String, Integer>>, Map.Entry<String, Integer>> accessor) {
        MutableMapSignal<String, Integer> source = MapSignal.of();
        source.put("a", 1);
        Map.Entry<String, Integer> entry = accessor.apply(source.asReadOnly().entrySet());
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue(3));
        assertEquals(1, source.get("a"));
    }

    @Test
    void mapRejectsUserFunctionsBeforeExecutingThem() {
        MapSignal<String, Integer> view = MapSignal.<String, Integer>of().asReadOnly();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(UnsupportedOperationException.class, () -> view.computeIfAbsent("a", key -> calls.incrementAndGet()));
        assertThrows(UnsupportedOperationException.class, () -> view.compute("a", (key, value) -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
    }

    @Test
    void setViewIsReusedAndForwardsBatchesAndDerivedReads() {
        MutableSetSignal<String> source = SetSignal.of();
        SetSignal<String> view = source.asReadOnly();
        assertSame(view, source.asReadOnly());
        assertSame(view, view.asReadOnly());
        assertSame(view, view.get());
        assertFalse(view instanceof MutableSetSignal<?>);
        Signal<Integer> size = view.map(Set::size);
        assertEquals(0, size.get());
        assertEquals(0, SignalTestAccess.entryCount(source));
        List<Integer> observed = new ArrayList<>();
        try (Subscription ignored = size.onDirty(() -> observed.add(size.get()))) {
            source.batch(() -> {
                source.add("a");
                source.add("b");
            });
            source.remove("a");
            assertEquals(List.of(2, 1), observed);
            assertEquals(Set.of("b"), view);
        }
        assertEquals(0, SignalTestAccess.entryCount(source));
        source.clear();
        assertEquals(0, size.get());
        assertEquals(List.of(2, 1), observed);
    }

    @Test
    void mapViewIsReusedAndKeepsPreviouslyObtainedViewsLive() {
        MutableMapSignal<String, Integer> source = MapSignal.of();
        MapSignal<String, Integer> view = source.asReadOnly();
        assertSame(view, source.asReadOnly());
        assertSame(view, view.asReadOnly());
        assertSame(view, view.get());
        assertFalse(view instanceof MutableMapSignal<?, ?>);
        Set<String> keys = view.keySet();
        var values = view.values();
        Set<Map.Entry<String, Integer>> entries = view.entrySet();
        Signal<Integer> total = view.map(map -> map.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(0, total.get());
        assertEquals(0, SignalTestAccess.entryCount(source));
        List<Integer> observed = new ArrayList<>();
        try (Subscription ignored = total.onDirty(() -> observed.add(total.get()))) {
            source.batch(() -> {
                source.put("a", 1);
                source.put("b", 2);
            });
            source.put("a", 3);
            assertEquals(List.of(3, 5), observed);
            assertEquals(Set.of("a", "b"), keys);
            assertEquals(Set.of(3, 2), new HashSet<>(values));
            assertEquals(Set.of(Map.entry("a", 3), Map.entry("b", 2)), entries);
        }
        assertEquals(0, SignalTestAccess.entryCount(source));
        source.clear();
        assertEquals(0, total.get());
        assertEquals(List.of(3, 5), observed);
        assertEquals(Set.of(), keys);
    }
}
