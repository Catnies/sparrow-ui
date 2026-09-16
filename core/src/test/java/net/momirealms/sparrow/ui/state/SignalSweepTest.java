package net.momirealms.sparrow.ui.state;

import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalSweepTest {

    @Test
    void aHeldMappedNodeReleasesItsSourceOnTheFirstInvalidationAfterItsSubscribersDied() {
        MutableSignal<Integer> source = Signal.of(0);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) source;
        Signal<Integer> mapped = source.map(Function.identity());
        WeakReference<?> probe = subscribeAndDrop(mapped);

        assertEquals(1, internal.entryCount(), "有订阅时派生节点挂在来源上");
        GcSupport.awaitCollected(probe);
        source.set(1);

        assertEquals(0, internal.entryCount(), "下游死后的第一次来源失效就该把来源订阅收掉");
        assertEquals(1, mapped.get(), "节点自己还活着, 拉取照常");
    }

    @Test
    void aHeldCombinedNodeReleasesEverySourceNotJustTheOneThatInvalidated() {
        MutableSignal<Integer> left = Signal.of(0);
        MutableSignal<Integer> right = Signal.of(0);
        AbstractSignal<Integer> internalLeft = (AbstractSignal<Integer>) left;
        AbstractSignal<Integer> internalRight = (AbstractSignal<Integer>) right;
        Signal<Integer> combined = Signals.combine(left, right, (a, b) -> a + b);
        WeakReference<?> probe = subscribeAndDrop(combined);

        assertEquals(1, internalLeft.entryCount());
        assertEquals(1, internalRight.entryCount());
        GcSupport.awaitCollected(probe);
        left.set(1);

        assertEquals(0, internalLeft.entryCount());
        assertEquals(0, internalRight.entryCount(), "只有左边失效, 右边的订阅也要一起放手");
    }

    @Test
    void aHeldPartitionHandleReleasesItsForwardOnTheFirstPartitionChange() {
        MutableKeyedSignal<String, Integer> keyed = KeyedSignal.of(key -> 0);
        AbstractKeyedSignal<String, Integer, ?> internal = (AbstractKeyedSignal<String, Integer, ?>) keyed;
        Signal<Integer> handle = keyed.at("k");
        AbstractSignal<Integer> partition = internal.partition("k");
        WeakReference<?> probe = subscribeAndDrop(handle);

        assertEquals(1, partition.entryCount(), "句柄有订阅者时转发挂在分区上");
        GcSupport.awaitCollected(probe);
        keyed.set("k", 1);

        assertEquals(0, partition.entryCount(), "下游死后的第一次分区失效就该把转发收掉");
        assertEquals(1, handle.get(), "句柄自己还活着, 拉取照常");
    }

    private static WeakReference<?> subscribeAndDrop(Signal<?> signal) {
        Object captured = new Object();
        signal.onDirty(captured::hashCode);
        return new WeakReference<>(captured);
    }
}
