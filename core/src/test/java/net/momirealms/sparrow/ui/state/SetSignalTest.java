package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Bindings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetSignalTest {

    private final Bindings bindings = new Bindings();
    private SetSignal<String> abc() {
        return SetSignal.wrap(new LinkedHashSet<>(List.of("a", "b", "c")));
    }

    private AtomicInteger countNotifications(Signal<?> signal) {
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        return notifications;
    }

    static Stream<Arguments> mutationPaths() {
        return Stream.of(
                path("add", s -> s.add("d"), Set.of("a", "b", "c", "d")),
                path("addAll", s -> s.addAll(List.of("c", "d", "e")), Set.of("a", "b", "c", "d", "e")),
                path("remove", s -> s.remove("b"), Set.of("a", "c")),
                path("removeAll", s -> s.removeAll(List.of("a", "x")), Set.of("b", "c")),
                path("retainAll", s -> s.retainAll(List.of("a")), Set.of("a")),
                path("removeIf", s -> s.removeIf("c"::equals), Set.of("a", "b")),
                path("clear", Set::clear, Set.of()),
                path("iterator.remove", s -> {
                    Iterator<String> it = s.iterator();
                    it.next();
                    it.remove();
                }, Set.of("b", "c"))
        );
    }

    private static Arguments path(String name, Consumer<SetSignal<String>> action, Set<String> expected) {
        return Arguments.of(name, action, expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutationPaths")
    void everyMutationPathNotifiesExactlyOnce(String name, Consumer<SetSignal<String>> action, Set<String> expected) {
        SetSignal<String> signal = this.abc();
        AtomicInteger notifications = this.countNotifications(signal);
        action.accept(signal);

        assertEquals(expected, Set.copyOf(signal), name + " 的终态");
        assertEquals(1, notifications.get(), name + " 应恰好通知一次");
    }

    @Test
    void ineffectiveChangesDoNotNotify() {
        SetSignal<String> signal = this.abc();
        AtomicInteger notifications = this.countNotifications(signal);
        signal.add("a");
        signal.addAll(List.of("a", "b"));
        signal.addAll(List.of());
        signal.remove("x");
        signal.removeAll(List.of("x"));
        signal.retainAll(List.of("a", "b", "c"));
        signal.removeIf("x"::equals);
        SetSignal.of().clear();

        assertEquals(0, notifications.get());
        assertEquals(Set.of("a", "b", "c"), Set.copyOf(signal));
    }

    @Test
    void containsDerivationOnlyFiresWhenMembershipFlips() {
        SetSignal<String> signal = SetSignal.of();
        Signal<Boolean> hasA = signal.mapDistinct(s -> s.contains("a"));
        AtomicInteger notifications = this.countNotifications(hasA);
        signal.add("b");
        signal.add("c");

        assertEquals(0, notifications.get(), "别的元素进出不影响 contains(a)");
        signal.add("a");

        assertEquals(1, notifications.get());
        assertTrue(hasA.get());
        signal.remove("a");

        assertEquals(2, notifications.get());
    }

    @Test
    void batchMergesAndEqualityIsByIdentity() {
        SetSignal<String> signal = SetSignal.of();
        AtomicInteger notifications = this.countNotifications(signal);
        signal.batch(() -> {
            signal.add("a");
            signal.add("b");
        });

        assertEquals(1, notifications.get());
        assertNotEquals(signal, Set.of("a", "b"));
        assertTrue(Set.of("a", "b").equals(signal), "普通 Set 按内容看它");
        assertSame(signal, signal.get());
    }
}
