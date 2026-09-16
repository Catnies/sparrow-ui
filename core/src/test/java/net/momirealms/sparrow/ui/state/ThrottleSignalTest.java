package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.state.DelayTestSupport.TimeBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottleSignalTest {

    private final Bindings bindings = new Bindings();

    @BeforeEach
    void installDelayers() {
        DelayTestSupport.install();
    }

    @AfterEach
    void restoreDelayers() {
        DelayTestSupport.restore();
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void firstChangeGoesOutAtOnceAndTheRestOfTheWindowIsMergedIntoOneTrailingNotice(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> throttled = base.throttle(input, 3);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> throttled.onDirty(() -> received.add(throttled.get())));
        input.set(1);

        assertEquals(List.of(1), received);
        base.advance(1);
        input.set(2);
        base.advance(1);
        input.set(3);

        assertEquals(List.of(1), received, "窗口内不该通知");
        base.advance(1);

        assertEquals(List.of(1, 3), received, "窗口到期补发一次");
        base.advance(1);
        input.set(4);

        assertEquals(List.of(1, 3), received, "距补发才过 1 格, 还在窗口里");
        base.advance(2);

        assertEquals(List.of(1, 3, 4), received);
        base.advance(3);

        assertFalse(base.delayer().scheduled(), "没有待发时窗口到期就关, 不再占任务");
        input.set(5);

        assertEquals(List.of(1, 3, 4, 5), received);
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void valueStaysAtTheLastNoticeUntilTheTrailingEdge(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> throttled = base.throttle(input, 2);
        this.bindings.bind(() -> throttled.onDirty(() -> {
        }));
        input.set(1);
        input.set(2);

        assertEquals(2, input.get());
        assertEquals(1, throttled.get(), "窗口内读到的还是前沿那一次的值");
        base.advance(2);

        assertEquals(2, throttled.get());
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void withoutSubscribersItIsATransparentReadAndHoldsNoTask(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        AbstractSignal<Integer> internalInput = (AbstractSignal<Integer>) input;
        Signal<Integer> throttled = base.throttle(input, 3);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) throttled;
        long before = internal.version();
        input.set(7);

        assertEquals(7, throttled.get());
        assertTrue(internal.version() > before);
        assertFalse(base.delayer().scheduled());
        assertEquals(0, internalInput.entryCount());
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void subscribingDoesNotReplayChangesMadeBeforeIt(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> throttled = base.throttle(input, 2);
        input.set(5);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> throttled.onDirty(() -> received.add(throttled.get())));
        base.advance(10);

        assertEquals(List.of(), received, "订阅前的变化并进基线, 不补发");
        assertEquals(5, throttled.get());
        input.set(6);

        assertEquals(List.of(6), received);
    }
}
