package net.momirealms.sparrow.ui.state.internal;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.state.AsyncSignal;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.MutablePlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.PlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.Signal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignalEqualityTest {

    private final Bindings bindings = new Bindings();

    @Test
    void sameValueDecidesWhetherAWriteCountsAsAChange() {
        MutableSignal<String> signal = Signal.of("green", (a, b) -> a == b);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        signal.set(new String("green"));

        assertEquals(1, invalidations.get());
        signal.set(signal.get());

        assertEquals(1, invalidations.get());
    }

    @Test
    void aNonReflexiveSameValueOnlyCostsExtraInvalidations() {
        MutableSignal<String> signal = Signal.of("same", (a, b) -> false);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        signal.set(signal.get());
        signal.set(signal.get());

        assertEquals(2, invalidations.get(), "写回同一个引用也当成变了");
        assertEquals("same", signal.get(), "多发的只是失效, 值没被弄乱");
    }

    @Test
    void sameValueIsOnlyCalledWhenBothValuesAreNonNull() {
        AtomicInteger calls = new AtomicInteger();
        MutableSignal<String> signal = Signal.of("green", (a, b) -> {
            calls.incrementAndGet();
            return a.equals(b);
        });
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        signal.set(null);

        assertEquals(1, invalidations.get());
        assertEquals(0, calls.get());
        signal.set(null);

        assertEquals(1, invalidations.get());
        assertEquals(0, calls.get());
        signal.set("blue");

        assertEquals(2, invalidations.get());
        assertEquals(0, calls.get());
        signal.set("red");

        assertEquals(3, invalidations.get());
        assertEquals(1, calls.get());
    }

    @Test
    void updateAlsoGoesThroughSameValue() {
        MutableSignal<Coin> signal = Signal.of(new Coin(7, "起始"), SignalEqualityTest::sameAmount);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        signal.update(coin -> new Coin(coin.amount(), "换个标签"));

        assertEquals(0, invalidations.get());
        assertEquals("起始", signal.get().label());
        signal.update(coin -> new Coin(coin.amount() + 1, "涨了"));

        assertEquals(1, invalidations.get());
        assertEquals(8, signal.get().amount());
    }

    @Test
    void defaultFactoriesKeepUsingEquals() {
        MutableSignal<Coin> signal = Signal.of(new Coin(7, "起始"));
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        signal.set(new Coin(7, "起始"));

        assertEquals(0, invalidations.get());
        signal.set(new Coin(7, "换个标签"));

        assertEquals(1, invalidations.get());
    }

    @Test
    void nullSameValueIsRejectedAtTheFactory() {
        assertThrows(NullPointerException.class, () -> Signal.of("green", null));
        assertThrows(NullPointerException.class, () -> Signal.of("green").mapDistinct(value -> value, null));
    }

    @Test
    void mapDistinctUsesSameValueToTruncate() {
        MutableSignal<Coin> source = Signal.of(new Coin(7, "起始"));
        Signal<Coin> derived = source.mapDistinct(coin -> coin, SignalEqualityTest::sameAmount);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> derived.onDirty(invalidations::incrementAndGet));
        source.set(new Coin(7, "换个标签"));

        assertEquals(0, invalidations.get());
        source.set(new Coin(9, "涨了"));

        assertEquals(1, invalidations.get());
        assertEquals(9, derived.get().amount());
    }

    @Test
    void sameValueFailureOnSetPropagatesToTheCaller() {
        MutableSignal<String> signal = Signal.of("green", (a, b) -> {
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, () -> signal.set("blue"));
        assertEquals("green", signal.get());
    }

    @Test
    void sameValueFailureInMapDistinctIsIsolated() {
        MutableSignal<String> source = Signal.of("apple");
        Signal<String> derived = source.mapDistinct(value -> value, (a, b) -> {
            throw new IllegalStateException("boom");
        });
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            this.bindings.bind(() -> derived.onDirty(() -> {
            }));
            source.set("banana");

            assertEquals(1, probe.failures().size());
            assertEquals("banana", source.get());
        }
    }

    @Test
    void asyncLoadJudgedSameProducesNoInvalidation() {
        ManualExecutor executor = new ManualExecutor();
        AtomicReference<Coin> loaded = new AtomicReference<>(new Coin(7, "第一次"));
        AsyncSignal<Coin> signal = Signal.async(new Coin(7, "占位"), executor, loaded::get, SignalEqualityTest::sameAmount);
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
        executor.drain();

        assertEquals(0, invalidations.get());
        assertEquals("占位", signal.get().label());
        loaded.set(new Coin(9, "第二次"));
        signal.dirty();
        executor.drain();

        assertEquals(1, invalidations.get());
        assertEquals(9, signal.get().amount());
    }

    @Test
    void sameValueFailureDuringLoadIsIsolatedAndRecoverable() {
        ManualExecutor executor = new ManualExecutor();
        AtomicBoolean explode = new AtomicBoolean(true);
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            AsyncSignal<Coin> signal = Signal.async(new Coin(7, "占位"), executor, () -> new Coin(9, "装载结果"), (a, b) -> {
                if (explode.get()) {
                    throw new IllegalStateException("boom");
                }
                return sameAmount(a, b);
            });
            AtomicInteger invalidations = new AtomicInteger();
            this.bindings.bind(() -> signal.onDirty(invalidations::incrementAndGet));
            executor.drain();

            assertEquals(1, probe.failures().size());
            assertEquals("占位", signal.get().label());
            assertEquals(0, invalidations.get());
            explode.set(false);
            signal.dirty();

            assertEquals(1, executor.pending());
            executor.drain();

            assertEquals(1, invalidations.get());
            assertEquals("装载结果", signal.get().label());
            assertEquals(1, probe.failures().size());
        }
    }

    @Test
    void keyedSetAndUpdateUseSameValue() {
        MutableKeyedSignal<String, Coin> signal = KeyedSignal.of(key -> new Coin(0, key), SignalEqualityTest::sameAmount);
        Signal<Coin> alice = signal.at("alice");
        AtomicInteger invalidations = new AtomicInteger();
        this.bindings.bind(() -> alice.onDirty(invalidations::incrementAndGet));

        assertEquals("alice", signal.get("alice").label());
        signal.set("alice", new Coin(0, "换个标签"));

        assertEquals(0, invalidations.get());
        assertEquals("alice", alice.get().label());
        signal.update("alice", coin -> new Coin(coin.amount(), "再换一次"));

        assertEquals(0, invalidations.get());
        signal.set("alice", new Coin(5, "涨了"));

        assertEquals(1, invalidations.get());
        assertEquals(5, alice.get().amount());
    }

    @Test
    void keyedPartitionsDoNotShareTheirVerdict() {
        MutableKeyedSignal<String, Coin> signal = KeyedSignal.of(key -> new Coin(0, key), SignalEqualityTest::sameAmount);
        AtomicInteger aliceInvalidations = new AtomicInteger();
        AtomicInteger bobInvalidations = new AtomicInteger();
        Signal<Coin> alice = signal.at("alice");
        Signal<Coin> bob = signal.at("bob");
        this.bindings.bind(() -> alice.onDirty(aliceInvalidations::incrementAndGet));
        this.bindings.bind(() -> bob.onDirty(bobInvalidations::incrementAndGet));
        signal.get("alice");
        signal.get("bob");
        signal.set("alice", new Coin(3, "涨了"));

        assertEquals(1, aliceInvalidations.get());
        assertEquals(0, bobInvalidations.get());
    }

    @Test
    void keyedAsyncLoadJudgedSameProducesNoInvalidation() {
        ManualExecutor executor = new ManualExecutor();
        KeyedSignal<String, Coin> signal = KeyedSignal.async(
                new Coin(7, "占位"), executor, key -> new Coin("alice".equals(key) ? 7 : 9, key), SignalEqualityTest::sameAmount);
        Signal<Coin> alice = signal.at("alice");
        Signal<Coin> bob = signal.at("bob");
        AtomicInteger aliceInvalidations = new AtomicInteger();
        AtomicInteger bobInvalidations = new AtomicInteger();
        this.bindings.bind(() -> alice.onDirty(aliceInvalidations::incrementAndGet));
        this.bindings.bind(() -> bob.onDirty(bobInvalidations::incrementAndGet));
        alice.get();
        bob.get();
        executor.drain();

        assertEquals(0, aliceInvalidations.get());
        assertEquals("占位", alice.get().label());
        assertEquals(1, bobInvalidations.get());
        assertEquals("bob", bob.get().label());
    }

    @Test
    void everyFactoryHasASameValueForm() throws NoSuchMethodException {
        assertEquals(MutableSignal.class, factory(Signal.class, "of", Object.class, BiPredicate.class).getReturnType());
        assertEquals(AsyncSignal.class, factory(Signal.class, "async", Object.class, Executor.class, Supplier.class, BiPredicate.class).getReturnType());
        assertEquals(Signal.class, factory(Signal.class, "mapDistinct", Function.class, BiPredicate.class).getReturnType());
        assertEquals(MutableKeyedSignal.class, factory(KeyedSignal.class, "of", Function.class, BiPredicate.class).getReturnType());
        assertEquals(KeyedSignal.class, factory(KeyedSignal.class, "async", Object.class, Executor.class, Function.class, BiPredicate.class).getReturnType());
        assertEquals(MutablePlayerKeyedSignal.class, factory(PlayerKeyedSignal.class, "of", Function.class, BiPredicate.class).getReturnType());
        assertEquals(PlayerKeyedSignal.class, factory(PlayerKeyedSignal.class, "async", Object.class, Executor.class, Function.class, BiPredicate.class).getReturnType());
    }

    private static Method factory(Class<?> owner, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);

        assertNull(method.getAnnotation(Deprecated.class));
        return method;
    }

    private static boolean sameAmount(Coin a, Coin b) {
        return a.amount() == b.amount();
    }

    private record Coin(int amount, String label) {
    }
}
