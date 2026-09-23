package net.momirealms.sparrow.ui.state.internal;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.state.AsyncSignal;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalReentryGuardTest {

    private final Bindings bindings = new Bindings();

    @Test
    void aListenerInvalidatingItsOwnSignalIsRefused() {
        MutableSignal<Integer> signal = Signal.of(0);
        AtomicInteger others = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(() -> signal.set(signal.get() + 1)));
        this.bindings.bind(() -> signal.onDirty(others::incrementAndGet));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            signal.set(1);

            assertEquals(1, probe.failures().size());
            assertReentrancy(probe.failures().get(0));
            assertEquals(1, others.get(), "别的订阅者照常收到这一次失效");
            assertEquals(2, signal.get(), "写入本身落了, 被拒的只是那次重入通知");
        }
    }

    @Test
    void aFeedbackLoopAcrossTwoSignalsBreaksAtTheClosingEdge() {
        MutableSignal<Integer> first = Signal.of(0);
        MutableSignal<Integer> second = Signal.of(0);
        this.bindings.bind(() -> first.onDirty(() -> second.set(first.get())));
        this.bindings.bind(() -> second.onDirty(() -> first.set(second.get() + 1)));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            first.set(1);

            assertEquals(1, probe.failures().size(), "环闭合的那一跳被拒, 不再是 StackOverflowError");
            assertReentrancy(probe.failures().get(0));
            assertEquals(2, first.get());
            assertEquals(1, second.get());
        }
    }

    @Test
    void writingADifferentSignalFromAListenerIsFine() {
        MutableSignal<Integer> source = Signal.of(0);
        MutableSignal<Integer> mirror = Signal.of(0);
        this.bindings.bind(() -> source.onDirty(() -> mirror.set(source.get() * 10)));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            source.set(3);

            assertEquals(List.of(), probe.messages());
            assertEquals(30, mirror.get());
        }
    }

    @Test
    void derivedNodesStillChainAcrossNestedDispatch() {
        MutableSignal<Integer> source = Signal.of(0);
        Signal<Integer> doubled = source.mapDistinct(value -> value * 2);
        Signal<String> label = doubled.map(value -> "v" + value);
        List<String> received = new ArrayList<>();
        this.bindings.bind(() -> label.onDirty(() -> received.add(label.get())));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            source.set(3);

            assertEquals(List.of(), probe.messages(), "一层套一层派发的是不同节点, 不该被当成重入");
            assertEquals(List.of("v6"), received);
        }
    }

    @Test
    void consecutiveDispatchesOnTheSameSignalAreFine() {
        MutableSignal<Integer> signal = Signal.of(0);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            signal.set(1);
            signal.set(2);
            signal.set(3);

            assertEquals(List.of(), probe.messages());
            assertEquals(3, invalidations.get());
        }
    }

    @Test
    void concurrentDispatchOnOneSignalIsNotMistakenForReentrancy() throws InterruptedException {
        MutableSignal<Integer> signal = Signal.of(0);
        this.bindings.bind(() -> signal.onDirty(() -> {
        }));
        int rounds = 5000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int writer = 0; writer < 4; writer++) {
            int offset = writer * rounds;
            threads.add(new Thread(() -> {
                if (!awaitStart(start)) return;
                for (int i = 1; i <= rounds; i++) {
                    signal.set(offset + i);
                }
            }, "reentry-writer-" + writer));
        }
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            for (Thread thread : threads) {
                thread.start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }

            assertEquals(List.of(), probe.messages(), "不同线程各自派发, 一次都不该误判");
        }
    }

    @Test
    void anAsyncLoaderInvalidatingItselfIsRefusedAndRecovers() {
        ManualExecutor executor = new ManualExecutor();
        AtomicReference<AsyncSignal<Integer>> holder = new AtomicReference<>();
        AtomicBoolean selfDirty = new AtomicBoolean(true);
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            AsyncSignal<Integer> signal = Signal.async(0, executor, () -> {
                if (selfDirty.getAndSet(false)) {
                    holder.get().dirty();
                }
                return 7;
            });
            holder.set(signal);
            executor.drain();

            assertEquals(1, probe.failures().size());
            assertReentrancy(probe.failures().get(0));
            assertEquals(0, signal.get(), "这一轮装载整个作废, 旧值保持");
            signal.dirty();

            assertEquals(1, executor.pending());
            executor.drain();

            assertEquals(7, signal.get());
            assertEquals(1, probe.failures().size());
        }
    }

    @Test
    void aKeyedAsyncLoaderInvalidatingItsPartitionIsRefused() {
        ManualExecutor executor = new ManualExecutor();
        AtomicReference<KeyedSignal<String, Integer>> holder = new AtomicReference<>();
        AtomicBoolean selfDirty = new AtomicBoolean(true);
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            KeyedSignal<String, Integer> keyed = KeyedSignal.async(0, executor, key -> {
                if (selfDirty.getAndSet(false)) {
                    holder.get().dirty(key);
                }
                return 7;
            });
            holder.set(keyed);
            keyed.get("k");
            executor.drain();

            assertEquals(1, probe.failures().size());
            assertReentrancy(probe.failures().get(0));
            assertEquals(0, keyed.get("k"));
        }
    }

    private static void assertReentrancy(Throwable failure) {
        IllegalStateException typed = assertInstanceOf(IllegalStateException.class, failure);

        assertTrue(typed.getMessage().startsWith("Reentrant invalidation"), "消息要认得出来: " + typed.getMessage());
    }

    private static boolean awaitStart(CountDownLatch start) {
        try {
            start.await();
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
