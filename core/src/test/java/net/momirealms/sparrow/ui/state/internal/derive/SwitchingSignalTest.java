package net.momirealms.sparrow.ui.state.internal.derive;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.ExceptionHandlerProbe;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.ManualExecutor;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import net.momirealms.sparrow.ui.state.internal.keyed.KeyedSignalTestAccess;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwitchingSignalTest {

    private final Bindings bindings = new Bindings();

    @Test
    void valueComesFromThePartitionSelectedByKey() {
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(String::length);
        MutableSignal<String> key = Signal.of("a");
        Signal<Integer> switching = Signals.switching(pages, key);

        assertEquals(1, switching.get());
        key.set("bbb");

        assertEquals(3, switching.get());
    }

    @Test
    void switchingKeyNotifiesDownstreamOnce() {
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(String::length);
        MutableSignal<String> key = Signal.of("a");
        Signal<Integer> switching = Signals.switching(pages, key);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> switching.onDirty(invalidations::incrementAndGet));
        key.set("bb");

        assertEquals(1, invalidations.get(), "换一次 key 只通知一次");
        assertEquals(2, switching.get());
    }

    @Test
    void selectedPartitionInvalidationReachesDownstream() {
        Map<String, Integer> backing = new HashMap<>(Map.of("a", 1, "b", 2));
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(backing::get);
        MutableSignal<String> key = Signal.of("a");
        Signal<Integer> switching = Signals.switching(pages, key);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> switching.onDirty(invalidations::incrementAndGet));
        backing.put("a", 10);
        pages.dirty("a");

        assertEquals(1, invalidations.get());
        assertEquals(10, switching.get());
    }

    @Test
    void previousPartitionStopsNotifyingAfterSwitchingAway() {
        Map<String, Integer> backing = new HashMap<>(Map.of("a", 1, "b", 2));
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(backing::get);
        MutableSignal<String> key = Signal.of("a");
        Signal<Integer> switching = Signals.switching(pages, key);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> switching.onDirty(invalidations::incrementAndGet));
        key.set("b");

        assertEquals(1, invalidations.get());
        backing.put("a", 10);
        pages.dirty("a");

        assertEquals(1, invalidations.get(), "旧分区的失效不应再传播");
        assertEquals(2, switching.get());
        backing.put("b", 20);
        pages.dirty("b");

        assertEquals(2, invalidations.get(), "切过去的分区应当接上通知");
        assertEquals(20, switching.get());
    }

    @Test
    void removingTheSelectedPartitionRebuildsOnNextReadWithoutNotifying() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(key -> loads.incrementAndGet());
        MutableSignal<String> key = Signal.of("a");
        Signal<Integer> switching = Signals.switching(pages, key);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> switching.onDirty(invalidations::incrementAndGet));

        assertEquals(1, switching.get());
        pages.remove("a");

        assertEquals(0, invalidations.get(), "本节点不该自己发明一次通知");
        assertEquals(2, switching.get(), "下次读取经句柄重建分区");
    }

    @Test
    void recomputedKeyWithSameValueNeitherSwitchesNorNotifies() {
        MutableSignal<Integer> raw = Signal.of(10);
        Signal<String> key = raw.map(value -> value >= 10 ? "big" : "small");
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(String::length);
        Signal<Integer> switching = Signals.switching(pages, key);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> switching.onDirty(invalidations::incrementAndGet));

        assertEquals(3, switching.get());
        raw.set(20);

        assertEquals(0, invalidations.get(), "key 值没变时不应通知下游");
        assertEquals(3, switching.get());
    }

    @Test
    void onlyTheSelectedPartitionIsLoaded() {
        ManualExecutor executor = new ManualExecutor();
        List<String> loaded = new ArrayList<>();
        KeyedSignal<String, String> pages = KeyedSignal.async("...", executor, key -> {
            loaded.add(key);
            return "loaded-" + key;
        });
        MutableSignal<String> key = Signal.of("p0");
        Signal<String> switching = Signals.switching(pages, key);

        assertEquals("...", switching.get(), "首载完成前给占位值");
        executor.drain();

        assertEquals("loaded-p0", switching.get());
        assertEquals(List.of("p0"), loaded, "没被选中的分区不装载");
        key.set("p1");

        assertEquals("...", switching.get(), "新分区首载完成前同样是占位值");
        executor.drain();

        assertEquals("loaded-p1", switching.get());
        assertEquals(List.of("p0", "p1"), loaded);
    }

    @Test
    void switchingBackReusesTheAlreadyLoadedPartition() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> pages = KeyedSignal.async(0, executor, key -> loads.incrementAndGet());
        MutableSignal<String> key = Signal.of("p0");
        Signal<Integer> switching = Signals.switching(pages, key);
        switching.get();
        executor.drain();
        key.set("p1");
        switching.get();
        executor.drain();

        assertEquals(2, loads.get());
        key.set("p0");

        assertEquals(1, switching.get(), "翻回来直接给上次的结果");
        assertEquals(0, executor.pending(), "不该再排一次装载");
        assertEquals(2, loads.get());
    }

    @Test
    void derivedNodeSeesNewValueWithoutAnySubscriber() {
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(String::length);
        MutableSignal<String> key = Signal.of("a");
        Signal<Integer> doubled = Signals.switching(pages, key).map(value -> value * 2);

        assertEquals(2, doubled.get());
        assertEquals(2, doubled.get());
        key.set("bbb");

        assertEquals(6, doubled.get(), "无订阅者时派生也不能停在旧值上");
    }

    @Test
    void switchingAwayReleasesThePreviousHandle() {
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(String::length);
        MutableSignal<String> key = Signal.of("a");
        Signal<Integer> switching = Signals.switching(pages, key);

        assertEquals(1, switching.get());
        WeakReference<Signal<Integer>> probe = new WeakReference<>(pages.at("a"));

        assertEquals(1, KeyedSignalTestAccess.handleCount(pages));
        key.set("bbb");

        assertEquals(3, switching.get());
        GcSupport.awaitCollected(probe);

        assertEquals(1, KeyedSignalTestAccess.handleCount(pages), "只剩当前选中的那一个句柄");
    }

    @Test
    void droppingTheSwitchingSignalReleasesItsHandle() {
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(String::length);
        MutableSignal<String> key = Signal.of("a");
        Signal<Integer> switching = Signals.switching(pages, key);
        Bindings host = new Bindings();
        bindNoop(host, switching);

        assertEquals(1, switching.get());
        assertEquals(1, KeyedSignalTestAccess.handleCount(pages), "选中的分区句柄由 switching 强持有");
        WeakReference<Signal<Integer>> probe = new WeakReference<>(switching);
        switching = null;
        host = null;
        GcSupport.awaitCollected(probe);

        assertEquals(0, KeyedSignalTestAccess.handleCount(pages), "switching 被回收后句柄不该赖在表里");
    }

    @Test
    void keySwapDuringActivationClosesTheReplacedForwarding() {
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(String::length);
        AbstractSignal<?> first = (AbstractSignal<?>) pages.at("a");
        AbstractSignal<?> second = (AbstractSignal<?>) pages.at("bb");
        MutableSignal<Integer> base = Signal.of(0);
        AtomicInteger mappings = new AtomicInteger();
        Signal<String> key = base.map(value -> {
            if (mappings.incrementAndGet() == 1) {
                base.set(1);
                return "a";
            }
            return "bb";
        });
        Signal<Integer> switching = Signals.switching(pages, key);
        this.bindings.bind(() -> switching.onDirty(() -> {
        }));

        assertEquals(0, SignalTestAccess.entryCount(first));
        assertEquals(1, SignalTestAccess.entryCount(second));
    }

    @Test
    void deadHostReleasesTheChainEvenWhenEveryInvalidationIsSwallowed() {
        MutableSignal<Integer> raw = Signal.of(10);
        AbstractSignal<Integer> internalRaw = (AbstractSignal<Integer>) raw;
        MutableKeyedSignal<String, Integer> pages = KeyedSignal.of(String::length);
        Signal<Integer> switching = Signals.switching(pages, raw.map(value -> "big"));
        Bindings dead = new Bindings();
        WeakReference<Bindings> probe = new WeakReference<>(dead);
        dead.bind(() -> switching.onDirty(() -> {
        }));

        assertEquals(1, SignalTestAccess.entryCount(internalRaw));
        dead = null;
        GcSupport.awaitCollected(probe);
        for (int value = 1; value <= 5; value++) {
            raw.set(value);
        }

        assertEquals(0, SignalTestAccess.entryCount(internalRaw), "持有方死亡后整条切换链应当解挂");
    }

    @Test
    void failedVersionSnapshotLeavesTheSelectionUntouched() {
        MutableSignal<Integer> stable = Signal.of(1);
        MutableSignal<Integer> raw = Signal.of(10);
        AtomicBoolean explode = new AtomicBoolean();
        Signal<Integer> exploding = raw.mapDistinct(value -> {
            if (explode.get()) {
                throw new IllegalStateException("version exploded");
            }
            return value;
        });
        this.bindings.bind(() -> exploding.onDirty(() -> {
        }));
        MutableSignal<String> key = Signal.of("stable");
        Signal<Integer> switching = Signals.switching(Map.of("stable", stable, "exploding", exploding), key);
        this.bindings.bind(() -> switching.onDirty(() -> {
        }));
        explode.set(true);
        try (ExceptionHandlerProbe ignored = new ExceptionHandlerProbe()) {
            raw.set(20);
            key.set("exploding");
        }

        assertEquals(1, SignalTestAccess.entryCount(stable), "换源没成, 旧来源的转发要原样留着");
        assertEquals(1, SignalTestAccess.entryCount(exploding), "失败的换源不该在新来源上留下转发");
        explode.set(false);

        assertEquals(20, switching.get());
        assertEquals(0, SignalTestAccess.entryCount(stable), "换源成功才摘掉旧转发");
        assertEquals(2, SignalTestAccess.entryCount(exploding), "测试自己那条, 加上换过来的这条");
    }

    private static void bindNoop(Bindings host, Signal<?> signal) {
        host.bind(() -> signal.onDirty(() -> {
        }));
    }
}
