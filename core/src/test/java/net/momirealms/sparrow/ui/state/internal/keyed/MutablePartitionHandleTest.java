package net.momirealms.sparrow.ui.state.internal.keyed;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.MutablePlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.PlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.ManualExecutor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class MutablePartitionHandleTest {

    private final Bindings bindings = new Bindings();

    @Test
    void handleWritesAndKeyedWritesShareOnePath() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 0);
        MutableSignal<Integer> handle = signal.at("k");
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> handle.onDirty(invalidations::incrementAndGet));
        handle.set(3);

        assertEquals(3, signal.get("k"));
        assertEquals(1, invalidations.get());
        handle.update(value -> value + 4);

        assertEquals(7, signal.get("k"));
        assertEquals(2, invalidations.get());
        signal.set("k", 9);

        assertEquals(9, handle.get());
        assertEquals(3, invalidations.get());
        signal.set("k", 9);

        assertEquals(3, invalidations.get(), "判等跳过对句柄这一侧同样生效");
    }

    @Test
    void repeatedAtReturnsTheSameWritableHandle() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        MutableSignal<Integer> handle = signal.at("k");

        assertSame(handle, signal.at("k"));
    }

    @Test
    void asyncKeyedHandleStaysReadOnly() throws NoSuchMethodException {
        ManualExecutor executor = new ManualExecutor();
        KeyedSignal<String, Integer> signal = KeyedSignal.async(0, executor, key -> 1);
        Signal<Integer> handle = signal.at("k");

        assertFalse(handle instanceof MutableSignal, "异步分区的值来自 loader, 写进去下一轮就被盖掉");
        assertEquals(KeyedSignal.class,
                KeyedSignal.class.getMethod("async", Object.class, Executor.class, Function.class).getReturnType());
    }

    @Test
    void readAfterEvictionRunsTheLoaderOnce() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> {
            loads.incrementAndGet();
            return 1;
        });
        MutableSignal<Integer> handle = signal.at("k");

        assertEquals(1, handle.get());
        signal.remove("k");
        loads.set(0);

        assertEquals(1, handle.get());
        assertEquals(1, loads.get());
    }

    @Test
    void writeAfterEvictionSkipsTheLoader() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> {
            loads.incrementAndGet();
            return 1;
        });
        MutableSignal<Integer> handle = signal.at("k");

        assertEquals(1, handle.get());
        signal.remove("k");
        loads.set(0);
        handle.set(9);

        assertEquals(0, loads.get(), "整个值都被覆盖了, 不需要基值");
        assertEquals(9, signal.get("k"));
    }

    @Test
    void updateAfterEvictionRunsTheLoaderForTheBaseValue() {
        AtomicInteger loads = new AtomicInteger();
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> {
            loads.incrementAndGet();
            return 1;
        });
        MutableSignal<Integer> handle = signal.at("k");

        assertEquals(1, handle.get());
        signal.remove("k");
        loads.set(0);
        handle.update(value -> value + 1);

        assertEquals(1, loads.get(), "update 要拿到基值才能套上去");
        assertEquals(2, signal.get("k"));
    }

    @Test
    void writeAfterEvictionRebuildsThePartitionAndForwarding() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 1);
        MutableSignal<Integer> handle = signal.at("k");
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> handle.onDirty(invalidations::incrementAndGet));
        signal.remove("k");
        handle.set(5);

        assertEquals(5, handle.get());
        assertEquals(1, invalidations.get());
        assertSame(handle, signal.at("k"), "删除重建不换句柄");
        signal.set("k", 7);

        assertEquals(2, invalidations.get(), "重建后失效转发必须恢复");
    }

    @Test
    void writingToAnEvictedKeyBringsThePartitionBack() {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 0);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) signal;
        MutableSignal<Integer> handle = signal.at("k");
        handle.set(3);
        signal.remove("k");

        assertEquals(0, internal.partitionCount());
        handle.set(5);

        assertEquals(1, internal.partitionCount());
        assertEquals(5, signal.get("k"));
    }

    @Test
    void concurrentUpdatesThroughOneHandleAllLand() throws InterruptedException {
        MutableKeyedSignal<String, Integer> signal = KeyedSignal.of(key -> 0);
        MutableSignal<Integer> handle = signal.at("k");
        int rounds = 2000;
        CountDownLatch start = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                start.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < rounds; i++) {
                handle.update(value -> value + 1);
            }
        };
        Thread first = new Thread(task, "handle-writer-1");
        Thread second = new Thread(task, "handle-writer-2");
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        assertEquals(2 * rounds, handle.get());
    }

    @Test
    void playerKeyedHandlesFollowTheSameSplit() throws NoSuchMethodException {
        assertEquals(MutableSignal.class, MutableKeyedSignal.class.getMethod("at", Object.class).getReturnType());
        assertEquals(Signal.class, KeyedSignal.class.getMethod("at", Object.class).getReturnType());
        assertEquals(MutableSignal.class, MutablePlayerKeyedSignal.class.getDeclaredMethod("at", Player.class).getReturnType());
        assertEquals(Signal.class, PlayerKeyedSignal.class.getDeclaredMethod("at", Player.class).getReturnType());
    }
}
