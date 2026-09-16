package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionHandleForwardingTest {

    private final Bindings bindings = new Bindings();

    @Test
    void takingAHandleLeavesThePartitionWithoutSubscribers() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        Signal<Integer> handle = signal.at("k");

        assertEquals(1, internal.partitionCount(), "分区照建");
        assertEquals(0, internal.partition("k").entryCount(), "没人订阅句柄, 分区上就不该有转发");
        assertEquals(1, handle.get());
    }

    @Test
    void takingAHandleOnAnAsyncSourceNeitherSubscribesNorLoads() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, executor, key -> loads.incrementAndGet());
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        Signal<Integer> handle = signal.at("k");
        executor.drain();

        assertEquals(0, internal.partition("k").entryCount());
        assertEquals(0, loads.get());
        assertEquals(0, handle.get());
    }

    @Test
    void forwardFollowsTheHandleSubscribers() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        Signal<Integer> handle = signal.at("k");
        AbstractSignal<Integer> partition = internal.partition("k");
        Subscription first = handle.onDirty(() -> {
        });

        assertEquals(1, partition.entryCount(), "第一个订阅者到来才挂转发");
        Subscription second = handle.onDirty(() -> {
        });

        assertEquals(1, partition.entryCount(), "再多订阅者也只有一条转发");
        first.close();

        assertEquals(1, partition.entryCount(), "还有订阅者时转发留着");
        second.close();

        assertEquals(0, partition.entryCount(), "最后一个走了转发就摘");
    }

    @Test
    void anUnsubscribedHandleStillSeesChangesThroughPull() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        Signal<Integer> handle = signal.at("k");
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) handle;
        Signal<Integer> doubled = handle.map(value -> value * 2);

        assertEquals(2, doubled.get());
        long before = internal.version();
        signal.set("k", 5);

        assertTrue(internal.version() > before, "没人订阅也要随分区变化推进版本, 下游缓存才会失配");
        assertEquals(10, doubled.get(), "纯拉取的下游在无订阅时也要读到新值");
        assertEquals(5, handle.get());
    }

    @Test
    void evictionAloneAdvancesTheVersionSoPullingDownstreamRereads() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> loads.incrementAndGet());
        Signal<Integer> handle = signal.at("k");
        Signal<Integer> doubled = handle.map(value -> value * 2);

        assertEquals(2, doubled.get(), "第一次装载得 1");
        signal.remove("k");

        assertEquals(4, doubled.get(), "重建后第二次装载得 2");
    }

    @Test
    void evictionAndRebuildWhileSubscribedRehooksTheForward() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        Signal<Integer> handle = signal.at("k");
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> handle.onDirty(() -> received.add(handle.get())));
        signal.remove("k");

        assertEquals(0, internal.partitionCount());
        assertEquals(1, handle.get(), "读值重建分区");
        assertEquals(1, internal.partition("k").entryCount(), "重建出的分区要接回转发");
        signal.set("k", 7);

        assertEquals(List.of(7), received);
    }

    @Test
    void subscribingWhileEvictedHooksUpOnRebuild() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        Signal<Integer> handle = signal.at("k");
        signal.remove("k");
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> handle.onDirty(() -> received.add(handle.get())));

        assertEquals(0, internal.partitionCount());
        assertSame(handle, signal.at("k"), "重建不换句柄");
        assertEquals(1, internal.partition("k").entryCount(), "重建时把等着的订阅者接上");
        signal.set("k", 3);

        assertEquals(List.of(3), received);
    }

    @Test
    void changesBetweenUnsubscribeAndResubscribeAreVisibleButNotReplayed() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        Signal<Integer> handle = signal.at("k");
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) handle;
        List<Integer> received = new ArrayList<>();
        Subscription subscription = handle.onDirty(() -> received.add(handle.get()));
        subscription.close();
        long before = internal.version();
        signal.set("k", 2);
        this.bindings.bind(() -> handle.onDirty(() -> received.add(handle.get())));

        assertEquals(List.of(), received, "退订期间的变化不补发");
        assertEquals(2, handle.get(), "但读得到");
        assertTrue(internal.version() > before);
        signal.set("k", 3);

        assertEquals(List.of(3), received, "重新订阅之后照常转发");
    }

    @Test
    void switchingAwayReleasesTheOldPartitionAtOnce() {
        MutableKeyedSignal<Integer, String> pages = KeyedSignal.of(page -> "page-" + page);
        AbstractKeyedSignal<Integer, String, ?> internal = (AbstractKeyedSignal<Integer, String, ?>) pages;
        MutableSignal<Integer> page = Signal.of(0);
        Signal<String> shown = Signals.switching(pages, page);
        List<String> received = new ArrayList<>();
        this.bindings.bind(() -> shown.onDirty(() -> received.add(shown.get())));

        assertEquals(1, internal.partition(0).entryCount(), "当前页的分区有一条转发");
        page.set(1);

        assertEquals(0, internal.partition(0).entryCount());
        assertEquals(1, internal.partition(1).entryCount());
        assertEquals(List.of("page-1"), received);
    }
}
