package net.momirealms.sparrow.ui.state.internal;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.AsyncSignal;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalKnownDefectsTest {

    private final Bindings bindings = new Bindings();

    @Test
    void staleReloadMustNotSwallowConcurrentInvalidation() {
        Map<String, String> backing = new HashMap<>(Map.of("k", "A"));
        AtomicReference<Runnable> duringLoad = new AtomicReference<>();
        MutableKeyedSignal<String, String> signal = KeyedSignal.of(key -> {
            String value = backing.get(key);
            Runnable hook = duringLoad.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return value;
        });

        assertEquals("A", signal.get("k"));
        backing.put("k", "B");
        signal.dirty("k");
        duringLoad.set(() -> {
            backing.put("k", "C");
            signal.dirty("k");
        });

        assertEquals("C", signal.get("k"), "装载窗口内到达的失效不能丢失");
        assertEquals("C", signal.get("k"));
    }

    @Test
    void swallowedInvalidationMustNotFreezeDerivedCache() {
        Map<String, Integer> backing = new HashMap<>(Map.of("k", 1));
        AtomicReference<Runnable> duringLoad = new AtomicReference<>();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> {
            Integer value = backing.get(key);
            Runnable hook = duringLoad.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return value;
        });
        Signal<String> label = signal.at("k").map(value -> "v" + value);

        assertEquals("v1", label.get());
        backing.put("k", 2);
        signal.dirty("k");
        duringLoad.set(() -> {
            backing.put("k", 3);
            signal.dirty("k");
        });
        signal.get("k");

        assertEquals("v3", label.get());
    }

    @Test
    void staleReloadMustNotReturnUncommittedValue() {
        AtomicReference<Runnable> duringLoad = new AtomicReference<>();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> {
            Runnable hook = duringLoad.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return 1;
        });
        duringLoad.set(() -> signal.set("k", 99));

        assertEquals(99, signal.get("k"), "CAS 失败后必须重读已提交状态");
    }

    @Test
    void removeMustRetireInFlightAsyncLoadAndStayQuiet() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, executor, key -> loads.incrementAndGet());
        List<Object> notifications = new ArrayList<>();
        this.bindings.bind(() -> signal.at("k").onDirty(() -> notifications.add(new Object())));
        signal.remove("k");
        executor.drain();

        assertEquals(0, loads.get(), "已驱逐分区的在途装载不应执行");
        assertEquals(0, notifications.size(), "驱逐不通知订阅者");
    }

    @Test
    void viewFollowsPartitionAcrossEviction() {
        Map<String, Integer> backing = new HashMap<>(Map.of("k", 1));
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(backing::get);
        Signal<Integer> view = signal.at("k");

        assertEquals(1, view.get());
        backing.put("k", 2);
        signal.remove("k");

        assertEquals(2, view.get(), "驱逐后句柄应跟到重建的分区");
    }

    @Test
    void viewVersionMustKeepAdvancingAcrossEviction() {
        Map<String, Integer> backing = new HashMap<>(Map.of("k", 1));
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(backing::get);
        Signal<String> label = signal.at("k").map(value -> "v" + value);

        assertEquals("v1", label.get());
        backing.put("k", 2);
        signal.remove("k");

        assertEquals("v2", label.get(), "句柄版本必须单调递增, 不能透传分区版本");
    }

    @Test
    void viewSubscriptionSurvivesEviction() {
        Map<String, Integer> backing = new HashMap<>(Map.of("k", 1));
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(backing::get);
        Signal<Integer> view = signal.at("k");
        List<Integer> received = new ArrayList<>();
        Subscription subscription = view.onDirty(() -> received.add(view.get()));
        signal.remove("k");
        received.clear();
        signal.set("k", 7);

        assertTrue(!subscription.isClosed(), "句柄订阅不应随驱逐终止");
        assertEquals(List.of(7), received, "驱逐后的新分区变更应继续送达");
    }

    @Test
    void deadWeakEntriesMustBeReapedWithoutDispatch() {
        MutableSignal<String> season = Signal.of("spring");
        AbstractSignal<String> internal = (AbstractSignal<String>) season;
        AtomicInteger notifications = new AtomicInteger();
        Bindings dead = new Bindings();
        WeakReference<Bindings> probe = new WeakReference<>(dead);
        dead.bind(() -> season.onDirty(notifications::incrementAndGet));
        dead = null;
        GcSupport.awaitCollected(probe);
        Bindings live = new Bindings();
        live.bind(() -> season.onDirty(notifications::incrementAndGet));

        assertEquals(1, internal.entryCount(), "新绑定到来时应顺带清理死条目");
        Reference.reachabilityFence(live);
    }

    @Test
    void deadWeakHostMustDeactivateSwallowingMapDistinctChain() {
        MutableSignal<Long> tick = Signal.of(0L);
        AbstractSignal<Long> internalTick = (AbstractSignal<Long>) tick;
        Signal<Long> day = tick.mapDistinct(value -> value / 24000L);
        AtomicInteger notifications = new AtomicInteger();
        Bindings dead = new Bindings();
        WeakReference<Bindings> probe = new WeakReference<>(dead);
        dead.bind(() -> day.onDirty(notifications::incrementAndGet));

        assertEquals(1, internalTick.entryCount());
        dead = null;
        GcSupport.awaitCollected(probe);
        for (long i = 1; i <= 5; i++) {
            tick.set(i);
        }

        assertEquals(0, internalTick.entryCount(), "持有方死亡后整条派生链应当解挂");
    }

    @Test
    void rejectedScheduleMustNotSwallowConcurrentInvalidation() {
        ManualExecutor delegate = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<AsyncSignal<Integer>> holder = new AtomicReference<>();
        AtomicInteger rejections = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.async(0, command -> {
            if (rejections.get() > 0) {
                rejections.decrementAndGet();
                holder.get().dirty();
                throw new RejectedExecutionException("busy");
            }
            delegate.execute(command);
        }, loads::incrementAndGet);
        holder.set(signal);
        delegate.drain();

        assertEquals(1, loads.get());
        rejections.set(1);
        try (ExceptionHandlerProbe ignored = new ExceptionHandlerProbe()) {
            signal.dirty();
        }
        delegate.drain();

        assertEquals(2, loads.get(), "并发登记的失效不应被回滚抹掉");
    }
}
