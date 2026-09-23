package net.momirealms.sparrow.ui.state.internal.keyed;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.SlotSequence;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.ExceptionHandlerProbe;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import net.momirealms.sparrow.ui.state.internal.player.PlayerSignalTestRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeysSignalTest {

    private final Bindings bindings = new Bindings();

    @Test
    void creatingAPartitionInvalidatesKeysAndRevisitingDoesNot() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(String::length);
        Signal<Set<String>> keys = signal.keys();
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> keys.onDirty(invalidations::incrementAndGet));

        assertEquals(Set.of(), keys.get());
        signal.get("a");

        assertEquals(1, invalidations.get(), "get 建出新分区");
        assertEquals(Set.of("a"), keys.get());
        signal.get("a");
        signal.set("a", 5);
        signal.dirty("a");

        assertEquals(1, invalidations.get(), "已有分区再访问、写值、标脏都不碰 keys()");
        signal.at("b");

        assertEquals(2, invalidations.get(), "at 建出新分区");
        assertEquals(Set.of("a", "b"), keys.get());
        signal.at("b");
        signal.at("a");

        assertEquals(2, invalidations.get(), "已有分区再取句柄不碰 keys()");
    }

    @Test
    void removingInvalidatesAndAHandleRebuildBringsTheKeyBack() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(String::length);
        Signal<Set<String>> keys = signal.keys();
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> keys.onDirty(invalidations::incrementAndGet));
        Signal<Integer> handle = signal.at("a");

        assertEquals(1, invalidations.get());
        signal.remove("a");

        assertEquals(2, invalidations.get());
        assertEquals(Set.of(), keys.get(), "句柄还活着, 但行没了就不在 keys() 里");
        signal.remove("a");

        assertEquals(2, invalidations.get(), "删一个没有分区的 key 不失效");
        handle.get();

        assertEquals(3, invalidations.get(), "句柄跨删除重建分区, key 重新出现");
        assertEquals(Set.of("a"), keys.get());
    }

    @Test
    void clearEmptiesKeys() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(String::length);
        Signal<Set<String>> keys = signal.keys();
        signal.get("a");
        signal.get("b");
        signal.get("c");
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> keys.onDirty(invalidations::incrementAndGet));
        signal.clear();

        assertEquals(Set.of(), keys.get());
        assertEquals(1, invalidations.get(), "删完只派发一次");
    }

    @Test
    void snapshotsAreUnmodifiableAndDoNotFollowLaterChanges() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(String::length);
        signal.get("a");
        Set<String> before = signal.keys().get();

        assertThrows(UnsupportedOperationException.class, () -> before.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> before.remove("a"));
        signal.get("b");

        assertEquals(Set.of("a"), before, "先取的快照不受后续建行影响");
        assertEquals(Set.of("a", "b"), signal.keys().get());
    }

    @Test
    void theSnapshotIsCachedUntilThePartitionSetChanges() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(String::length);
        signal.get("a");
        Signal<Set<String>> keys = signal.keys();
        Set<String> first = keys.get();

        assertSame(first, keys.get(), "没有建行删行, 重复拉取应当拿到同一份");
        signal.get("b");
        Set<String> second = keys.get();

        assertNotSame(first, second, "建了一行就要重新复制");
        assertEquals(Set.of("a", "b"), second);
        assertSame(second, keys.get());
        signal.remove("b");

        assertEquals(Set.of("a"), keys.get(), "删行同样让缓存失效");
    }

    @Test
    void listenersMayTouchExistingPartitionsFromTheCallback() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(String::length);
        Signal<Set<String>> keys = signal.keys();
        AtomicInteger invalidations = new AtomicInteger();
        AtomicReference<String> justTouched = new AtomicReference<>();
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            this.bindings.bind(() -> keys.onDirty(() -> {
                invalidations.incrementAndGet();
                signal.get(justTouched.get());
                signal.at(justTouched.get());
                for (String key : keys.get()) {
                    signal.get(key);
                    signal.at(key);
                }
            }));
            justTouched.set("a");
            signal.get("a");
            justTouched.set("b");
            signal.get("b");
            justTouched.set("c");
            signal.at("c");
            justTouched.set("b");
            signal.remove("a");

            assertEquals(4, invalidations.get());
            assertEquals(List.of(), probe.failures(), "派发在 compute 之外, 回调里再碰主表既不抛 Recursive update 也不死锁");
            assertEquals(Set.of("b", "c"), keys.get());
        }
    }

    @Test
    void creatingANewPartitionFromTheCallbackIsAReentrantInvalidation() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(String::length);
        Signal<Set<String>> keys = signal.keys();
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            this.bindings.bind(() -> keys.onDirty(() -> signal.get("other")));
            signal.get("a");

            assertEquals(1, probe.failures().size());
            assertInstanceOf(IllegalStateException.class, probe.failures().get(0));
            assertTrue(probe.failures().get(0).getMessage().contains("Reentrant"));
            assertEquals(Set.of("a", "other"), keys.get(), "分区照样建好, 只是那一次派发被拦下");
        }
    }

    @Test
    void theNodeIsOnlyCreatedOnTheFirstKeysCall() throws ReflectiveOperationException {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(String::length);
        Field field = AbstractKeyedSignal.class.getDeclaredField("keys");
        field.setAccessible(true);
        signal.get("a");
        signal.at("b");
        signal.remove("a");

        assertNull(field.get(signal), "没人要过 keys() 之前建行删行不建节点");
        Signal<Set<String>> keys = signal.keys();

        assertNotNull(field.get(signal));
        assertSame(keys, signal.keys(), "之后每次都是同一个节点");
        assertEquals(Set.of("b"), keys.get(), "首次创建时就能看到之前建好的行");
    }

    @Test
    void droppedSubscriptionsAreReapedOnTheNextDispatch() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(String::length);
        AbstractSignal<Set<String>> node = (AbstractSignal<Set<String>>) signal.keys();
        WeakReference<?> probe = subscribeFromAnOwnerThatGoesAway(signal);

        assertEquals(1, SignalTestAccess.entryCount(node));
        GcSupport.awaitCollected(probe);
        signal.get("a");

        assertEquals(0, SignalTestAccess.entryCount(node), "持有方走后, 下一次建行派发把死条目清掉");
    }

    private static WeakReference<?> subscribeFromAnOwnerThatGoesAway(KeyedSignal<String, Integer> signal) {
        Bindings owner = new Bindings();
        owner.bind(() -> signal.keys().onDirty(() -> {}));
        return new WeakReference<>(owner);
    }

    @Test
    void concurrentCreationAndRemovalEndInAConsistentSnapshot() throws InterruptedException {
        MutableKeyedSignal<Integer, Integer> signal = KeyedSignal.of(key -> key);
        Signal<Set<Integer>> keys = signal.keys();
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> keys.onDirty(invalidations::incrementAndGet));
        int writers = 4;
        int keysPerWriter = 8;
        int operations = 2_000;
        List<Set<Integer>> expected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started = new CountDownLatch(writers);
        CountDownLatch finished = new CountDownLatch(writers);
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            for (int writer = 0; writer < writers; writer++) {
                int base = writer * keysPerWriter;
                threads.add(new Thread(() -> {
                    Random random = new Random(base);
                    Set<Integer> present = new HashSet<>();
                    started.countDown();
                    for (int i = 0; i < operations; i++) {
                        int key = base + random.nextInt(keysPerWriter);
                        if (random.nextBoolean()) {
                            signal.get(key);
                            present.add(key);
                        } else {
                            signal.remove(key);
                            present.remove(key);
                        }
                    }
                    expected.add(present);
                    finished.countDown();
                }));
            }
            AtomicBoolean sawAForeignKey = new AtomicBoolean();
            Thread reader = new Thread(() -> {
                while (!stop.get()) {
                    for (Integer key : keys.get()) {
                        if (key < 0 || key >= writers * keysPerWriter) sawAForeignKey.set(true);
                    }
                }
            });
            threads.forEach(Thread::start);
            reader.start();

            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertTrue(finished.await(30, TimeUnit.SECONDS));
            stop.set(true);
            reader.join(TimeUnit.SECONDS.toMillis(10));
            for (Thread thread : threads) thread.join(TimeUnit.SECONDS.toMillis(10));
            Set<Integer> reference = new HashSet<>();
            for (Set<Integer> part : expected) reference.addAll(part);

            assertEquals(reference, keys.get(), "终态快照等于各写入线程最后一步的并集");
            assertEquals(List.of(), probe.failures());
            assertFalse(sawAForeignKey.get(), "并发读到的每一份快照都只含真实 key");
            assertTrue(invalidations.get() > 0);
        }
    }

    @Nested
    class WithABukkitServer {
        private VirtualInventory vault;
        @BeforeEach
        void setUp() {
            PlayerSignalTestRuntime.install();
            this.vault = new VirtualInventory(9);
        }
        @AfterEach
        void tearDown() {
            PlayerSignalTestRuntime.restore();
        }
        @Test
        void quitEvictingSignalOwnsItsKeysView() {
            UUID uuid = UUID.randomUUID();
            MutableKeyedSignal<UUID, Integer> signal = KeyedSignal.of(key -> 0);
            Signals.evictOnQuit(signal);
            Signal<Set<UUID>> keys = signal.keys();

            assertSame(keys, signal.keys());
            signal.get(uuid);

            assertEquals(Set.of(uuid), keys.get());
        }
        @Test
        void aLobbyListFollowsTheKeysWhileRowUpdatesLeaveItAlone() {
            MutableKeyedSignal<String, Integer> rooms = KeyedSignal.of(name -> 0);
            Signal<List<String>> roster = rooms.keys().mapDistinct(names -> {
                List<String> sorted = new ArrayList<>(names);
                Collections.sort(sorted);
                return sorted;
            });
            rooms.set("b", 1);
            rooms.set("a", 1);
            AtomicInteger rosterInvalidations = new AtomicInteger();
            AtomicInteger rowAInvalidations = new AtomicInteger();
            KeysSignalTest.this.bindings.bind(() -> roster.onDirty(rosterInvalidations::incrementAndGet));
            KeysSignalTest.this.bindings.bind(() -> rooms.at("a").onDirty(rowAInvalidations::incrementAndGet));
            NormalPane pane = Pane.empty(9, 1);
            AtomicInteger projections = new AtomicInteger();
            pane.project(SlotSequence.all(pane.size()), roster, name -> {
                projections.incrementAndGet();
                return Element.inventory(this.vault, name.charAt(0) - 'a');
            }, Runnable::run);

            assertEquals(List.of("a", "b"), roster.get());
            assertEquals(2, projections.get());
            rooms.update("a", value -> value + 1);

            assertEquals(1, rowAInvalidations.get(), "那一行自己的订阅者收到失效");
            assertEquals(0, rosterInvalidations.get(), "名单没变, 列表不动");
            assertEquals(2, projections.get(), "投影一格都没重算");
            rooms.set("c", 1);

            assertEquals(1, rosterInvalidations.get(), "新行让名单失效");
            assertEquals(List.of("a", "b", "c"), roster.get());
            assertEquals(5, projections.get(), "名单变了才整体重投影");
            assertEquals(Element.inventory(this.vault, 2), pane.element(2));
            rooms.remove("b");

            assertEquals(List.of("a", "c"), roster.get());
            assertEquals(Element.inventory(this.vault, 2), pane.element(1));
            assertSame(Element.empty(), pane.element(2));
        }
    }
}
