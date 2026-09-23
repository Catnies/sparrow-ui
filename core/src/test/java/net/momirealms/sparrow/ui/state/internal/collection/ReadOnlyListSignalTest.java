package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.pane.page.Page;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MutableListSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadOnlyListSignalTest {

    static Stream<Arguments> mutations() {
        return Stream.of(
                mutation("add", list -> list.add("d")),
                mutation("add at index", list -> list.add(0, "d")),
                mutation("addFirst", list -> list.addFirst("d")),
                mutation("addLast", list -> list.addLast("d")),
                mutation("addAll", list -> list.addAll(List.of("d"))),
                mutation("addAll at index", list -> list.addAll(0, List.of("d"))),
                mutation("set", list -> list.set(0, "d")),
                mutation("replaceAll", list -> list.replaceAll(String::toUpperCase)),
                mutation("sort", list -> list.sort(Comparator.reverseOrder())),
                mutation("remove object", list -> list.remove("a")),
                mutation("remove index", list -> list.remove(0)),
                mutation("removeFirst", List::removeFirst),
                mutation("removeLast", List::removeLast),
                mutation("removeAll", list -> list.removeAll(List.of("a"))),
                mutation("retainAll", list -> list.retainAll(List.of("a"))),
                mutation("removeIf", list -> list.removeIf(value -> true)),
                mutation("clear", List::clear),
                mutation("iterator remove", list -> {
                    var iterator = list.iterator();
                    iterator.next();
                    iterator.remove();
                }),
                mutation("listIterator remove", list -> {
                    ListIterator<String> iterator = list.listIterator();
                    iterator.next();
                    iterator.remove();
                }),
                mutation("listIterator set", list -> {
                    ListIterator<String> iterator = list.listIterator(1);
                    iterator.previous();
                    iterator.set("d");
                }),
                mutation("listIterator add", list -> list.listIterator().add("d")),
                mutation("subList", list -> list.subList(0, 2).clear()),
                mutation("subList iterator", list -> {
                    var iterator = list.subList(0, 2).iterator();
                    iterator.next();
                    iterator.remove();
                }),
                mutation("reversed", list -> list.reversed().add("d")),
                mutation("reversed iterator", list -> {
                    var iterator = list.reversed().listIterator();
                    iterator.next();
                    iterator.set("d");
                }),
                mutation("nested views", list -> list.reversed().subList(0, 2).reversed().set(0, "d")),
                mutation("empty addAll", list -> list.addAll(List.of())),
                mutation("absent remove", list -> list.remove("missing")),
                mutation("no-op removeIf", list -> list.removeIf(value -> false))
        );
    }

    private static Arguments mutation(String name, Consumer<List<String>> action) {
        return Arguments.of(name, action);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void allMutationPathsRejectWrites(String name, Consumer<List<String>> action) {
        MutableListSignal<String> source = ListSignal.wrap(new ArrayList<>(List.of("a", "b", "c")));
        ListSignal<String> view = source.asReadOnly();
        AtomicInteger notifications = new AtomicInteger();
        try (Subscription ignored = source.onDirty(notifications::incrementAndGet)) {
            assertThrows(UnsupportedOperationException.class, () -> action.accept(view));
            assertThrows(UnsupportedOperationException.class, () -> action.accept(view.get()));
            assertEquals(List.of("a", "b", "c"), source);
            assertEquals(0, notifications.get());
        }
    }

    @Test
    void viewIsReusedAndReadsLiveContentWithoutSubscribing() {
        MutableListSignal<String> source = ListSignal.of();
        ListSignal<String> view = source.asReadOnly();
        assertSame(view, source.asReadOnly());
        assertSame(view, view.asReadOnly());
        assertSame(view, view.get());
        assertFalse(view instanceof MutableListSignal<?>);
        assertEquals(0, SignalTestAccess.entryCount(source));

        Signal<Integer> size = view.map(List::size);
        assertEquals(0, size.get());
        source.add("a");
        assertEquals(List.of("a"), view);
        assertEquals(1, size.get());
        source.add("b");
        assertEquals(List.of("b", "a"), view.reversed());
        assertEquals(2, size.get());
        assertEquals(0, SignalTestAccess.entryCount(source));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void snapshotSourcesRejectAllMutationPaths(String name, Consumer<List<String>> action) {
        List<String> snapshot = Collections.unmodifiableList(List.of("a", "b", "c"));
        ListSignal<String> view = new ReadOnlyListSignal<>(AbstractSignal.require(Signal.of(snapshot)));
        assertThrows(UnsupportedOperationException.class, () -> action.accept(view));
        assertThrows(UnsupportedOperationException.class, () -> action.accept(view.get()));
        assertSame(snapshot, view.get());
        assertEquals(List.of("a", "b", "c"), view);
    }

    @Test
    void subscriptionsForwardBatchesAndDetachFromSource() {
        MutableListSignal<String> source = ListSignal.of();
        ListSignal<String> view = source.asReadOnly();
        Signal<Integer> size = view.map(List::size);
        List<Integer> observed = new ArrayList<>();
        assertEquals(0, size.get());
        try (Subscription ignored = size.onDirty(() -> observed.add(size.get()))) {
            assertEquals(1, SignalTestAccess.entryCount(source));
            source.batch(() -> {
                source.add("a");
                source.add("b");
            });
            source.remove("a");
            assertEquals(List.of(2, 1), observed);
        }
        assertEquals(0, SignalTestAccess.entryCount(source));
        source.clear();
        assertEquals(List.of(2, 1), observed);
        assertEquals(0, size.get());
        try (Subscription ignored = size.onDirty(() -> observed.add(size.get()))) {
            source.add("c");
            assertEquals(List.of(2, 1, 1), observed);
        }
        assertEquals(0, SignalTestAccess.entryCount(source));
    }

    @Test
    void pageTracksReadOnlySourceAndClampsAfterRemoval() {
        MutableListSignal<String> source = ListSignal.wrap(new ArrayList<>(List.of("a", "b", "c")));
        Page<String> page = Page.of(source.asReadOnly(), 2);
        List<List<String>> observed = new ArrayList<>();
        try (Subscription ignored = page.content().onDirty(() -> observed.add(page.content().get()))) {
            page.setPage(1);
            assertEquals(List.of("c"), page.content().get());
            source.removeLast();
            assertEquals(0, page.page().get());
            assertEquals(List.of("a", "b"), page.content().get());
            assertEquals(List.of("a", "b"), observed.getLast());
        }
    }

    @Test
    void mergingTracksMembershipAndMemberChangesThroughReadOnlyView() {
        MutableSignal<Integer> first = Signal.of(0);
        MutableSignal<Integer> second = Signal.of(0);
        MutableListSignal<MutableSignal<Integer>> source = ListSignal.of();
        source.add(first);
        Signal<Long> merged = Signals.merging(source.asReadOnly(), member -> member);
        AtomicInteger notifications = new AtomicInteger();
        try (Subscription ignored = merged.onDirty(notifications::incrementAndGet)) {
            first.set(1);
            source.add(second);
            second.set(1);
            source.remove(first);
            int beforeRemovedChange = notifications.get();
            first.set(2);
            assertEquals(4, beforeRemovedChange);
            assertEquals(beforeRemovedChange, notifications.get());
        }
        assertEquals(0, SignalTestAccess.entryCount(source));
    }
}
