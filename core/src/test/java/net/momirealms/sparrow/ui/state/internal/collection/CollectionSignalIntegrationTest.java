package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.SlotSequence;
import net.momirealms.sparrow.ui.pane.page.Page;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.ExceptionHandlerProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionSignalIntegrationTest {

    private final Bindings bindings = new Bindings();
    private VirtualInventory vault;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.vault = new VirtualInventory(9);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pagesFollowAListSignalAndEachPageIsACopy() {
        ListSignal<Integer> list = ListSignal.of();
        for (int i = 0; i < 7; i++) list.add(i);
        Page<Integer> pages = Page.of(list, 3);
        List<List<Integer>> seen = new ArrayList<>();
        this.bindings.bind(() -> pages.content().onDirty(() -> seen.add(pages.content().get())));
        List<Integer> firstPage = pages.content().get();

        assertEquals(List.of(0, 1, 2), firstPage);
        list.add(0, 100);

        assertEquals(List.of(100, 0, 1), pages.content().get(), "list 变了, 当前页跟着变");
        assertEquals(List.of(0, 1, 2), firstPage, "之前拿到的那一页是复制, 不被后续变更改写");
        assertEquals(List.of(100, 0, 1), seen.get(seen.size() - 1));
        assertEquals(3, pages.count().get());
        list.add(7);

        assertEquals(3, pages.count().get());
        list.add(8);

        assertEquals(4, pages.count().get(), "页数随 list 长度走");
    }

    @Test
    void mergingEatsAListSignalOfSignals() {
        MutableSignal<Integer> left = Signal.of(0);
        MutableSignal<Integer> right = Signal.of(0);
        ListSignal<MutableSignal<Integer>> members = ListSignal.of();
        members.add(left);
        Signal<Long> anyChanged = Signals.merging(members, member -> member);
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> anyChanged.onDirty(notifications::incrementAndGet));
        left.set(1);

        assertEquals(1, notifications.get());
        right.set(1);

        assertEquals(1, notifications.get(), "还没加进来的成员不算");
        members.add(right);

        assertEquals(2, notifications.get(), "成员变了也失效, 不用换一个新集合");
        right.set(2);

        assertEquals(3, notifications.get(), "加进来之后的成员失效也算");
    }

    @Test
    void projectionOverACopyOnWriteDelegateSurvivesConcurrentWrites() throws InterruptedException {
        ListSignal<Integer> list = ListSignal.wrap(new CopyOnWriteArrayList<>());
        NormalPane pane = Pane.empty(9, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            pane.project(SlotSequence.all(pane.size()), list, slot -> Element.inventory(this.vault, slot % 9), executor);
            CountDownLatch done = new CountDownLatch(1);
            Thread writer = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    list.add(i % 9);
                    if (i % 3 == 0) list.remove(0);
                }
                done.countDown();
            });
            writer.start();

            assertTrue(done.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(List.of(), probe.failures(), "写时复制的 delegate 上投影不该撞上 ConcurrentModificationException");
        }
    }

    @Test
    void theWrapperIsWhatGetReturnsSoConsumersSeeTheLiveCollection() {
        ListSignal<String> list = ListSignal.of();
        Signal<List<String>> asSignal = list;

        assertSame(list, asSignal.get());
        list.add("a");

        assertEquals(List.of("a"), asSignal.get());
        assertNotSame(asSignal.get(), List.copyOf(asSignal.get()));
    }
}
