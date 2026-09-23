package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MutableListSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.ExceptionHandlerProbe;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListSignalTest {

    private final Bindings bindings = new Bindings();
    private MutableListSignal<String> abc() {
        return ListSignal.wrap(new ArrayList<>(List.of("a", "b", "c")));
    }

    private AtomicInteger countNotifications(Signal<?> signal) {
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        return notifications;
    }

    static Stream<Arguments> mutationPaths() {
        return Stream.of(
                path("add", l -> l.add("d"), List.of("a", "b", "c", "d")),
                path("add(int)", l -> l.add(0, "z"), List.of("z", "a", "b", "c")),
                path("addFirst", l -> l.addFirst("z"), List.of("z", "a", "b", "c")),
                path("addLast", l -> l.addLast("z"), List.of("a", "b", "c", "z")),
                path("addAll", l -> l.addAll(List.of("d", "e")), List.of("a", "b", "c", "d", "e")),
                path("addAll(int)", l -> l.addAll(1, List.of("x")), List.of("a", "x", "b", "c")),
                path("set", l -> l.set(1, "B"), List.of("a", "B", "c")),
                path("replaceAll", l -> l.replaceAll(String::toUpperCase), List.of("A", "B", "C")),
                path("sort", l -> l.sort(Comparator.reverseOrder()), List.of("c", "b", "a")),
                path("remove(Object)", l -> l.remove("b"), List.of("a", "c")),
                path("remove(int)", l -> l.remove(0), List.of("b", "c")),
                path("removeFirst", List::removeFirst, List.of("b", "c")),
                path("removeLast", List::removeLast, List.of("a", "b")),
                path("removeAll", l -> l.removeAll(List.of("a", "c")), List.of("b")),
                path("retainAll", l -> l.retainAll(List.of("a", "c")), List.of("a", "c")),
                path("removeIf", l -> l.removeIf("b"::equals), List.of("a", "c")),
                path("clear", List::clear, List.of()),
                path("iterator.remove", l -> {
                    Iterator<String> it = l.iterator();
                    it.next();
                    it.remove();
                }, List.of("b", "c")),
                path("listIterator.set", l -> {
                    ListIterator<String> it = l.listIterator();
                    it.next();
                    it.set("A");
                }, List.of("A", "b", "c")),
                path("listIterator.add", l -> {
                    ListIterator<String> it = l.listIterator(1);
                    it.add("x");
                }, List.of("a", "x", "b", "c")),
                path("listIterator.remove", l -> {
                    ListIterator<String> it = l.listIterator(3);
                    it.previous();
                    it.remove();
                }, List.of("a", "b")),
                path("subList.add", l -> l.subList(0, 1).add("x"), List.of("a", "x", "b", "c")),
                path("subList.clear", l -> l.subList(1, 3).clear(), List.of("a")),
                path("subList.set", l -> l.subList(1, 2).set(0, "B"), List.of("a", "B", "c")),
                path("subList.subList.remove", l -> l.subList(0, 3).subList(1, 3).remove("c"), List.of("a", "b")),
                path("subList.iterator.remove", l -> {
                    Iterator<String> it = l.subList(0, 2).iterator();
                    it.next();
                    it.remove();
                }, List.of("b", "c")),
                path("reversed.add", l -> l.reversed().add("z"), List.of("z", "a", "b", "c")),
                path("reversed.removeFirst", l -> l.reversed().removeFirst(), List.of("a", "b")),
                path("subList.reversed.set", l -> l.subList(0, 2).reversed().set(0, "B"), List.of("a", "B", "c"))
        );
    }

    private static Arguments path(String name, Consumer<MutableListSignal<String>> action, List<String> expected) {
        return Arguments.of(name, action, expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutationPaths")
    void everyMutationPathNotifiesExactlyOnce(String name, Consumer<MutableListSignal<String>> action, List<String> expected) {
        MutableListSignal<String> signal = this.abc();
        AtomicInteger notifications = this.countNotifications(signal);
        action.accept(signal);

        assertEquals(expected, List.copyOf(signal), name + " 的终态");
        assertEquals(1, notifications.get(), name + " 应恰好通知一次");
    }

    @Test
    void ineffectiveChangesDoNotNotify() {
        MutableListSignal<String> signal = this.abc();
        AtomicInteger notifications = this.countNotifications(signal);
        signal.set(0, signal.get(0));
        signal.addAll(List.of());
        signal.removeAll(List.of("x"));
        signal.retainAll(List.of("a", "b", "c"));
        signal.removeIf("x"::equals);
        signal.remove("x");
        ListIterator<String> it = signal.listIterator();
        it.set(it.next());
        ListSignal.of().clear();
        ListSignal.of().sort(null);

        assertEquals(0, notifications.get());
        assertEquals(List.of("a", "b", "c"), List.copyOf(signal));
    }

    @Test
    void subscribersReadTheChangedContentInsideTheCallback() {
        MutableListSignal<String> signal = ListSignal.of();
        List<List<String>> seen = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> seen.add(List.copyOf(signal))));
        signal.add("a");
        signal.add("b");

        assertEquals(List.of(List.of("a"), List.of("a", "b")), seen, "通知在变更之后, 回调里读到的是改完的内容");
    }

    @Test
    void distinctDerivationSeesEveryChangeBecauseNotificationFollowsTheChange() {
        MutableListSignal<String> signal = ListSignal.of();
        Signal<List<String>> copies = signal.mapDistinct(List::copyOf);
        List<List<String>> seen = new ArrayList<>();
        this.bindings.bind(() -> copies.onDirty(() -> seen.add(copies.get())));
        signal.add("a");
        signal.add("b");
        signal.remove("a");

        assertEquals(List.of(List.of("a"), List.of("a", "b"), List.of("b")), seen);
    }

    @Test
    void mapperRunsPerChangeNotPerRead() {
        MutableListSignal<String> signal = ListSignal.of();
        AtomicInteger mapperCalls = new AtomicInteger();
        Signal<Integer> size = signal.map(list -> {
            mapperCalls.incrementAndGet();
            return list.size();
        });
        this.bindings.bind(() -> size.onDirty(() -> {
        }));
        signal.add("a");
        signal.add("b");

        assertEquals(2, size.get());
        assertEquals(2, size.get());
        assertEquals(2, size.get());
        assertEquals(1, mapperCalls.get(), "三次读只算一次, 版本没变");
        signal.add("c");

        assertEquals(3, size.get());
        assertEquals(2, mapperCalls.get());
    }

    @Test
    void combiningTwoListSignalsRecomputesOnEitherChange() {
        MutableListSignal<String> left = ListSignal.of();
        MutableListSignal<String> right = ListSignal.of();
        Signal<Integer> total = Signals.combine(left, right, (l, r) -> l.size() + r.size());
        AtomicInteger notifications = this.countNotifications(total);
        left.add("a");
        right.add("b");

        assertEquals(2, notifications.get());
        assertEquals(2, total.get());
    }

    @Test
    void batchMergesChangesIntoOneNotification() {
        MutableListSignal<String> signal = ListSignal.of();
        AtomicInteger notifications = this.countNotifications(signal);
        signal.batch(() -> {
            signal.add("a");
            signal.add("b");
            signal.batch(() -> signal.add("c"));

            assertEquals(0, notifications.get(), "batch 里面不通知");
        });

        assertEquals(1, notifications.get(), "嵌套只有最外层通知一次");
        assertEquals(List.of("a", "b", "c"), List.copyOf(signal));
        signal.batch(() -> {
        });

        assertEquals(1, notifications.get(), "没有变更的 batch 不通知");
    }

    @Test
    void aThrowingBatchKeepsItsChangesAndStillNotifies() {
        MutableListSignal<String> signal = ListSignal.of();
        AtomicInteger notifications = this.countNotifications(signal);

        assertThrows(IllegalStateException.class, () -> signal.batch(() -> {
            signal.add("a");
            throw new IllegalStateException("boom");
        }));

        assertEquals(List.of("a"), List.copyOf(signal));
        assertEquals(1, notifications.get());
        signal.add("b");

        assertEquals(2, notifications.get());
    }

    @Test
    void equalityIsByIdentity() {
        MutableListSignal<String> signal = this.abc();
        MutableListSignal<String> other = this.abc();
        List<String> plain = new ArrayList<>(List.of("a", "b", "c"));

        assertNotEquals(signal, plain, "包装器不按内容判等");
        assertNotEquals(signal, other, "两个内容相同的包装器也不相等");
        assertEquals(signal, signal);
        assertTrue(plain.equals(signal), "普通 List 按内容看它, 这一侧是不对称的");
        Map<MutableListSignal<String>, String> registry = new HashMap<>();
        registry.put(signal, "entry");
        signal.add("d");

        assertEquals("entry", registry.get(signal));
        assertEquals(List.of("a", "b", "c"), signal.subList(0, 3), "视图按内容判等");
    }

    @Test
    void getReturnsTheWrapperItself() {
        MutableListSignal<String> signal = this.abc();

        assertSame(signal, signal.get());
    }

    @Test
    void deadSubscribersAreSweptOnTheNextChange() {
        MutableListSignal<String> signal = ListSignal.of();
        AbstractSignal<List<String>> internal = (AbstractSignal<List<String>>) signal;
        WeakReference<?> probe = subscribeAndDrop(signal);

        assertEquals(1, SignalTestAccess.entryCount(internal));
        GcSupport.awaitCollected(probe);
        signal.add("a");

        assertEquals(0, SignalTestAccess.entryCount(internal));
    }

    private static WeakReference<?> subscribeAndDrop(Signal<?> signal) {
        Object captured = new Object();
        signal.onDirty(captured::hashCode);
        return new WeakReference<>(captured);
    }

    @Test
    void concurrentWritersOnACopyOnWriteDelegateSettleConsistently() throws InterruptedException {
        MutableListSignal<Integer> signal = ListSignal.wrap(new CopyOnWriteArrayList<>());
        AtomicInteger notifications = this.countNotifications(signal);
        int writers = 4;
        int perWriter = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            for (int w = 0; w < writers; w++) {
                int base = w * perWriter;
                Thread writer = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perWriter; i++) {
                        signal.add(base + i);
                    }
                    for (int i = 0; i < perWriter; i += 2) {
                        signal.remove((Integer) (base + i));
                    }
                    done.countDown();
                });
                writer.start();
            }
            start.countDown();

            assertTrue(done.await(10, TimeUnit.SECONDS));
            List<Integer> expected = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                for (int i = 1; i < perWriter; i += 2) expected.add(w * perWriter + i);
            }
            List<Integer> actual = new ArrayList<>(signal);
            actual.sort(null);

            assertEquals(expected, actual);
            assertEquals(writers * perWriter + writers * perWriter / 2, notifications.get(), "每次有效变更恰好通知一次");
            assertEquals(List.of(), probe.failures());
        }
    }

    @Test
    void nullArgumentsAreRejectedUpFront() {
        assertThrows(NullPointerException.class, () -> ListSignal.wrap(null));
        assertThrows(NullPointerException.class, () -> ListSignal.of().beforeAdd(null));
        assertThrows(NullPointerException.class, () -> ListSignal.of().afterRemove(null));
    }

    @Test
    void nullElementsFollowTheDelegate() {
        MutableListSignal<String> permissive = ListSignal.wrap(new ArrayList<>());
        permissive.add(null);

        assertEquals(1, permissive.size());
        MutableListSignal<String> strict = ListSignal.wrap(new CopyOnWriteArrayList<>());
        strict.add(null);

        assertFalse(strict.isEmpty(), "写时复制的 List 也收 null, 装饰器不加自己的规矩");
    }
}
