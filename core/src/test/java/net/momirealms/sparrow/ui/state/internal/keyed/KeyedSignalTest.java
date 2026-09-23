package net.momirealms.sparrow.ui.state.internal.keyed;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.ExceptionHandlerProbe;
import net.momirealms.sparrow.ui.state.internal.GcSupport;
import net.momirealms.sparrow.ui.state.internal.ManualExecutor;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyedSignalTest {

    private final Bindings bindings = new Bindings();

    @Test
    void partitionsLoadLazilyAndCache() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> {
            loads.incrementAndGet();
            return key.length();
        });

        assertEquals(0, loads.get());
        assertEquals(5, signal.get("alpha"));
        assertEquals(5, signal.get("alpha"));
        assertEquals(1, loads.get());
    }

    @Test
    void partitionsAreIndependent() {
        Map<String, Integer> backing = new HashMap<>(Map.of("a", 1, "b", 2));
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(backing::get);
        AtomicInteger aInvalidations = new AtomicInteger();
        AtomicInteger bInvalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.at("a").onDirty(aInvalidations::incrementAndGet));
        this.bindings.bind(() -> signal.at("b").onDirty(bInvalidations::incrementAndGet));
        backing.put("a", 10);
        signal.dirty("a");

        assertEquals(1, aInvalidations.get());
        assertEquals(0, bInvalidations.get());
        assertEquals(10, signal.get("a"));
        assertEquals(2, signal.get("b"));
    }

    @Test
    void invalidateUnloadedKeyIsNoop() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> loads.incrementAndGet());
        signal.dirty("offline-player");

        assertEquals(0, loads.get());
    }

    @Test
    void invalidateMarksStaleAndNextGetRecomputes() {
        AtomicInteger loads = new AtomicInteger();
        Map<String, Integer> backing = new HashMap<>(Map.of("coins", 100));
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> {
            loads.incrementAndGet();
            return backing.get(key);
        });

        assertEquals(100, signal.get("coins"));
        backing.put("coins", 250);
        signal.dirty("coins");

        assertEquals(250, signal.get("coins"));
        assertEquals(2, loads.get());
    }

    @Test
    void getRetriesWhenLoadRacesWithInvalidation() {
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<Runnable> duringLoad = new AtomicReference<>();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> {
            int value = loads.incrementAndGet();
            Runnable hook = duringLoad.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return value;
        });
        duringLoad.set(() -> signal.dirty("k"));

        assertEquals(2, signal.get("k"), "首轮装载的提交被失效打断, 应当重试");
        assertEquals(2, loads.get());
    }

    @Test
    void getGivesUpAfterRepeatedInvalidationInsteadOfSpinning() {
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<MutableKeyedSignal<String, Integer>> holder = new AtomicReference<>();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> {
            int value = loads.incrementAndGet();
            holder.get().dirty(key);
            return value;
        });
        holder.set(signal);
        int observed = signal.get("k");

        assertTrue(loads.get() >= 2, "应当重试而不是一次就放弃");
        assertEquals(loads.get(), observed, "返回值必须是某次真实装载的结果");
    }

    @Test
    void concurrentInvalidationAndReadsConvergeOnLatestValue() throws InterruptedException {
        int iterations = 2000;
        AtomicInteger backing = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> backing.get());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 1; i <= iterations; i++) {
                backing.set(i);
                signal.dirty("k");
            }
            done.countDown();
        });
        writer.start();
        start.countDown();
        while (done.getCount() > 0) {
            Integer value = signal.get("k");

            assertTrue(value >= 0 && value <= iterations, "读到从未写入过的值: " + value);
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        backing.set(-1);
        signal.dirty("k");

        assertEquals(-1, signal.get("k"), "失效风暴后不能残留过期值");
    }

    @Test
    void setOverridesAndSkipsEqualValue() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 0);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.at("k").onDirty(invalidations::incrementAndGet));
        signal.set("k", 5);
        signal.set("k", 5);

        assertEquals(5, signal.get("k"));
        assertEquals(1, invalidations.get());
    }

    @Test
    void updateAppliesOnCurrentValue() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 10);
        signal.update("k", value -> value + 5);

        assertEquals(15, signal.get("k"));
    }

    @Test
    void updateOnStalePartitionRecomputesFirst() {
        Map<String, Integer> backing = new HashMap<>(Map.of("k", 1));
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(backing::get);

        assertEquals(1, signal.get("k"));
        backing.put("k", 100);
        signal.dirty("k");
        signal.update("k", value -> value + 1);

        assertEquals(101, signal.get("k"));
    }

    @Test
    void atReturnsFullyCapableSignalHandle() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        Signal<Integer> handle = signal.at("k");
        List<Integer> received = new ArrayList<>();
        Signal<Integer> scaled = handle.map(value -> value * 100);
        this.bindings.bind(() -> scaled.onDirty(() -> received.add(scaled.get())));
        signal.set("k", 3);

        assertEquals(List.of(300), received);
        assertSame(handle, signal.at("k"), "同一分区的句柄应是同一实例");
    }

    @Test
    void removeEvictsWithoutNotifying() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> loads.incrementAndGet());

        assertEquals(1, signal.get("k"));
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.at("k").onDirty(invalidations::incrementAndGet));
        signal.remove("k");

        assertEquals(0, invalidations.get());
        assertEquals(2, signal.get("k"), "驱逐后读取应重新装载全新分区");
    }

    @Test
    void removeDoesNotRebuildThePartition() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> loads.incrementAndGet());
        Signal<Integer> handle = signal.at("k");
        this.bindings.bind(() -> handle.onDirty(handle::get));

        assertEquals(1, signal.get("k"));
        signal.remove("k");

        assertEquals(1, loads.get());
    }

    @Test
    void handleReadAfterRemoveRebuildsAndReattaches() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        Signal<Integer> handle = signal.at("k");
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> handle.onDirty(invalidations::incrementAndGet));
        signal.remove("k");

        assertEquals(1, handle.get());
        signal.set("k", 5);

        assertEquals(1, invalidations.get(), "重建后失效转发必须恢复");
        assertEquals(5, handle.get());
    }

    @Test
    void atAfterRemoveRebuildsThePartitionAndForwarding() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        Signal<Integer> handle = signal.at("k");
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> handle.onDirty(invalidations::incrementAndGet));
        signal.remove("k");

        assertSame(handle, signal.at("k"), "删除重建不换句柄");
        signal.set("k", 5);

        assertEquals(1, invalidations.get(), "重建出的分区必须重新接上转发");
        assertEquals(5, handle.get());
    }

    @Test
    void clearEvictsAllPartitions() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> loads.incrementAndGet());
        signal.get("a");
        signal.get("b");
        signal.clear();
        signal.get("a");
        signal.get("b");

        assertEquals(4, loads.get());
    }

    @Test
    void clearEvictsQuietlyAndKeepsHandlesAlive() {
        Map<String, Integer> backing = new HashMap<>(Map.of("a", 1, "b", 2));
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(backing::get);
        Signal<Integer> handleA = signal.at("a");
        Signal<Integer> handleB = signal.at("b");
        AtomicInteger aInvalidations = new AtomicInteger();
        AtomicInteger bInvalidations = new AtomicInteger();
        this.bindings.bind(() -> handleA.onDirty(aInvalidations::incrementAndGet));
        this.bindings.bind(() -> handleB.onDirty(bInvalidations::incrementAndGet));
        backing.put("a", 10);
        backing.put("b", 20);
        signal.clear();

        assertEquals(0, aInvalidations.get(), "清空不通知, 与 remove 一致");
        assertEquals(0, bInvalidations.get());
        assertEquals(10, handleA.get(), "清空后句柄应跟到重建的分区");
        assertEquals(20, handleB.get());
    }

    @Test
    void invalidateAllInvalidatesEveryLoadedPartition() {
        Map<String, Integer> backing = new HashMap<>(Map.of("a", 1, "b", 2));
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(backing::get);
        signal.get("a");
        signal.get("b");
        backing.put("a", 10);
        backing.put("b", 20);
        signal.dirtyAll();

        assertEquals(10, signal.get("a"));
        assertEquals(20, signal.get("b"));
    }

    @Test
    void bulkReadsLeaveNoHandleBehind() {
        MutableKeyedSignal<Integer, Integer> signal = KeyedSignal.of(key -> key);
        AbstractKeyedSignal<Integer, Integer, ?> internal = (AbstractKeyedSignal<Integer, Integer, ?>) signal;
        for (int key = 0; key < 1000; key++) {
            signal.get(key);
            signal.remove(key);
        }

        assertEquals(0, internal.partitionCount());
        assertEquals(0, internal.handleCount(), "get/set/invalidate 不该留下句柄");
    }

    @Test
    void handlesAreCreatedOnlyByAt() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        signal.get("read");
        signal.set("written", 2);
        signal.dirty("read");

        assertEquals(0, internal.handleCount());
        Signal<Integer> handle = signal.at("handleed");

        assertEquals(1, internal.handleCount());
        Reference.reachabilityFence(handle);
    }

    @Test
    void handleAttachesToAPartitionCreatedBeforeIt() {
        Map<String, Integer> backing = new HashMap<>(Map.of("k", 1));
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(backing::get);
        signal.get("k");
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.at("k").onDirty(invalidations::incrementAndGet));
        signal.set("k", 2);

        assertEquals(1, invalidations.get());
    }

    @Test
    void repeatedAtDoesNotDuplicateForwarding() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.at("k").onDirty(invalidations::incrementAndGet));
        signal.at("k");
        signal.at("k");
        signal.set("k", 2);

        assertEquals(1, invalidations.get(), "重复取句柄不应让一次失效被转发多遍");
    }

    @Test
    void asyncKeyedRetriesInitialLoadAfterRejection() {
        ManualExecutor delegate = new ManualExecutor();
        AtomicInteger rejections = new AtomicInteger(1);
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, command -> {
            if (rejections.getAndUpdate(count -> count > 0 ? count - 1 : 0) > 0) {
                throw new java.util.concurrent.RejectedExecutionException("busy");
            }
            delegate.execute(command);
        }, key -> loads.incrementAndGet());
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            assertEquals(0, signal.get("k"));
            assertEquals(1, probe.failures().size(), "调度被拒应上报给统一异常处理器");
        }

        assertEquals(0, signal.get("k"));
        delegate.drain();

        assertEquals(1, loads.get());
        assertEquals(1, signal.get("k"));
    }

    @Test
    void asyncKeyedRepeatedReadRetriesRejectedInitialLoad() {
        ManualExecutor delegate = new ManualExecutor();
        AtomicInteger rejections = new AtomicInteger(1);
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, command -> {
            if (rejections.getAndUpdate(count -> count > 0 ? count - 1 : 0) > 0) {
                throw new java.util.concurrent.RejectedExecutionException("busy");
            }
            delegate.execute(command);
        }, key -> loads.incrementAndGet());
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            assertEquals(0, signal.get("k"), "首载还没完成, 读到的是占位值");
            assertEquals(1, probe.failures().size(), "调度被拒应上报给统一异常处理器");
        }
        signal.get("k");
        delegate.drain();

        assertEquals(1, loads.get());
        assertEquals(1, signal.get("k"));
    }

    @Test
    void atHooksUpForwardingWithoutPushingTheLoad() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, executor, key -> loads.incrementAndGet());
        Signal<Integer> handle = signal.at("k");
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> handle.onDirty(invalidations::incrementAndGet));

        assertEquals(0, executor.pending(), "取句柄不该推动装载");
        executor.drain();

        assertEquals(0, loads.get());
        assertEquals(0, handle.get(), "首载还没完成, 读到的是占位值");
        assertEquals(1, executor.pending());
        executor.drain();

        assertEquals(1, loads.get());
        assertEquals(1, handle.get());
        assertEquals(1, invalidations.get(), "订阅者在首载前就挂好了转发");
    }

    @Test
    void atOnAnEvictedKeyDoesNotReload() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, executor, key -> loads.incrementAndGet());
        signal.get("k");
        executor.drain();

        assertEquals(1, loads.get());
        signal.remove("k");
        signal.at("k");
        executor.drain();

        assertEquals(1, loads.get());
    }

    @Test
    void lateEvictionNoticeMustNotCauseDuplicateForwarding() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 0);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        PartitionHandle<String, Integer> handle = (PartitionHandle<String, Integer>) signal.at("k");
        AbstractSignal<Integer> stale = internal.partition("k");
        signal.remove("k");
        internal.partition("k");
        handle.onPartitionEvicted(stale);
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> handle.onDirty(notifications::incrementAndGet));
        signal.at("k");
        signal.set("k", 7);

        assertEquals(1, notifications.get(), "一次失效只应被转发一次");
    }

    @Test
    void reattachingReleasesTheForwardOnThePreviousPartition() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 0);
        MutableKeyedSignal<String, Integer> other = KeyedSignal.of(key -> 0);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        AbstractKeyedSignal<String, Integer, ?> otherInternal = (AbstractKeyedSignal<String, Integer, ?>) other;
        PartitionHandle<String, Integer> handle = (PartitionHandle<String, Integer>) signal.at("k");
        AbstractSignal<Integer> first = internal.partition("k");
        this.bindings.bind(() -> handle.onDirty(() -> {
        }));

        assertEquals(1, SignalTestAccess.entryCount(first));
        AbstractSignal<Integer> second = otherInternal.partition("k");
        handle.attach(second);

        assertEquals(0, SignalTestAccess.entryCount(first), "换挂后旧分区上不应残留转发");
        assertEquals(1, SignalTestAccess.entryCount(second), "转发跟到了新分区上");
    }

    @Test
    void attachPublishesOnlyAfterForwardIsRegistered() throws InterruptedException {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 0);
        PartitionHandle<String, Integer> handle = (PartitionHandle<String, Integer>) signal.at("k");
        this.bindings.bind(() -> handle.onDirty(() -> {
        }));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Signal<Integer> slow = Signal.of(0).mapDistinct(value -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return value;
        });
        Thread attacher = new Thread(() -> handle.attach((AbstractSignal<Integer>) slow));
        try {
            attacher.start();

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(handle.isAttachedTo((AbstractSignal<Integer>) slow), "转发建立完成前不得发布 attached");
        } finally {
            release.countDown();
            attacher.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertTrue(handle.isAttachedTo((AbstractSignal<Integer>) slow), "放行后挂载应当完成");
    }

    @Test
    void concurrentAccessAndEvictionKeepExactlyOneForwardPerHandle() throws InterruptedException {
        int keys = 4;
        int workers = 6;
        int iterations = 2000;
        Executor stalling = task -> {
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(50));
            task.run();
        };
        KeyedSignal<Integer, Integer> signal = KeyedSignal.async(0, stalling, key -> key);
        AbstractKeyedSignal<Integer, Integer, ?> internal = (AbstractKeyedSignal<Integer, Integer, ?>) signal;
        for (int key = 0; key < keys; key++) {
            Signal<Integer> handle = signal.at(key);
            this.bindings.bind(() -> handle.onDirty(() -> {
            }));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workerThreads = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            int offset = worker;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < iterations; i++) {
                    int key = (offset + i) % keys;
                    switch (i % 3) {
                        case 0 -> signal.at(key);
                        case 1 -> signal.get(key);
                        default -> signal.remove(key);
                    }
                }
            });
            workerThreads.add(thread);
            thread.start();
        }
        start.countDown();
        for (int i = 0; i < workerThreads.size(); i++) {
            workerThreads.get(i).join(TimeUnit.SECONDS.toMillis(30));
        }
        for (int key = 0; key < keys; key++) {
            assertEquals(1, SignalTestAccess.entryCount(internal.partition(key)), "分区上应当只有句柄的一条转发");
        }
    }

    @Test
    void unboundHandlesDoNotAccumulate() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 0);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        Signal<Integer> handle = signal.at("k");
        WeakReference<Signal<Integer>> probe = new WeakReference<>(handle);

        assertEquals(1, internal.handleCount());
        handle = null;
        GcSupport.awaitCollected(probe);

        assertEquals(0, internal.handleCount(), "无人持有的句柄应当被回收");
    }

    @Test
    void removedKeyDiesWithItsHandleWithoutAnyFurtherCall() {
        MutableKeyedSignal<Object, Integer> signal = KeyedSignal.of(key -> 0);
        AbstractKeyedSignal<Object, Integer, ?> internal = (AbstractKeyedSignal<Object, Integer, ?>) signal;
        Object key = new Object();
        WeakReference<Object> keyProbe = new WeakReference<>(key);
        Signal<Integer> handle = signal.at(key);
        signal.remove(key);
        WeakReference<Signal<Integer>> handleProbe = new WeakReference<>(handle);
        handle = null;
        key = null;
        GcSupport.awaitCollected(handleProbe);
        GcSupport.awaitCollected(keyProbe);

        assertEquals(0, internal.handleCount());
        assertEquals(0, internal.partitionCount());
        Reference.reachabilityFence(signal);
    }

    @Test
    void boundHandleKeepsForwardingAcrossRemoveAndRebuildUnderGcPressure() {
        MutableKeyedSignal<Key, Integer> signal = KeyedSignal.of(key -> 0);
        AbstractKeyedSignal<Key, Integer, ?> internal = (AbstractKeyedSignal<Key, Integer, ?>) signal;
        AtomicInteger notifications = new AtomicInteger();
        Bindings host = new Bindings();
        host.bind(() -> signal.at(new Key(1)).onDirty(notifications::incrementAndGet));
        signal.remove(new Key(1));
        GcSupport.pressure();

        assertEquals(1, internal.handleCount(), "分区删了, 被绑定的句柄仍寄放着, 不能随 GC 蒸发");
        assertEquals(0, internal.partitionCount());
        signal.set(new Key(1), 7);
        signal.set(new Key(1), 8);

        assertEquals(2, notifications.get(), "重建的分区必须接回寄放的句柄, 之后每次失效都照常转发");
        assertEquals(8, signal.get(new Key(1)));
        assertEquals(1, internal.handleCount(), "接回主表之后还是同一个句柄");
        Reference.reachabilityFence(host);
    }

    @Test
    void boundHandlesSurviveWithoutBeingHeldDirectly() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 0);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        AtomicInteger notifications = new AtomicInteger();
        Bindings host = new Bindings();
        host.bind(() -> signal.at("k").onDirty(notifications::incrementAndGet));
        GcSupport.pressure();

        assertEquals(1, internal.handleCount(), "仍被绑定的句柄不能被回收");
        signal.set("k", 7);

        assertEquals(1, notifications.get(), "绑定应当仍然收到失效");
        Reference.reachabilityFence(host);
    }

    @Test
    void rejectsNullKeys() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);

        assertThrows(NullPointerException.class, () -> signal.get(null));
        assertThrows(NullPointerException.class, () -> signal.dirty(null));
        assertThrows(NullPointerException.class, () -> signal.at(null));
        assertThrows(NullPointerException.class, () -> signal.remove(null));
    }

    @Test
    void asyncKeyedLoadsPerKeyInBackground() {
        ManualExecutor executor = new ManualExecutor();
        KeyedSignal<String, String> signal = KeyedSignal.async("...", executor, key -> "loaded-" + key);

        assertEquals("...", signal.get("a"));
        assertEquals("...", signal.get("b"));
        executor.drain();

        assertEquals("loaded-a", signal.get("a"));
        assertEquals("loaded-b", signal.get("b"));
    }

    @Test
    void asyncKeyedInvalidateSchedulesReload() {
        ManualExecutor executor = new ManualExecutor();
        Map<String, Integer> backing = new HashMap<>(Map.of("coins", 100));
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, executor, backing::get);
        signal.get("coins");
        executor.drain();

        assertEquals(100, signal.get("coins"));
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.at("coins").onDirty(invalidations::incrementAndGet));
        backing.put("coins", 250);
        signal.dirty("coins");

        assertEquals(100, signal.get("coins"), "重载完成前保持旧值");
        executor.drain();

        assertEquals(250, signal.get("coins"));
        assertEquals(1, invalidations.get());
    }

    @Test
    void asyncKeyedInvalidateUnloadedKeyIsNoop() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, executor, key -> loads.incrementAndGet());
        signal.dirty("offline");
        executor.drain();

        assertEquals(0, loads.get());
    }

    @Test
    void asyncKeyedPartitionSchedulesLoadOnlyOnce() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, executor, key -> loads.incrementAndGet());
        signal.at("k");
        signal.at("k");
        signal.get("k");
        executor.drain();

        assertEquals(1, loads.get());
    }

    private record Key(int id) {
    }
}
