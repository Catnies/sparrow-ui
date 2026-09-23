package net.momirealms.sparrow.ui.state.internal.time;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.ExceptionHandlerProbe;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import net.momirealms.sparrow.ui.state.internal.time.DelayTestSupport.TimeBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebounceSignalTest {

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
    void burstOfWritesNotifiesOnceAfterTheLastOneSettles(TimeBase base) {
        MutableSignal<String> input = Signal.of("");
        Signal<String> debounced = base.debounce(input, 3);
        List<String> received = new ArrayList<>();
        this.bindings.bind(() -> debounced.onDirty(() -> received.add(debounced.get())));
        for (String typed : List.of("a", "ab", "abc", "abcd", "abcde")) {
            input.set(typed);
            base.advance(1);
        }

        assertEquals(List.of(), received, "静默不满 3 格不该通知");
        base.advance(1);

        assertEquals(List.of(), received);
        base.advance(1);

        assertEquals(List.of("abcde"), received);
        base.advance(10);

        assertEquals(List.of("abcde"), received, "静默之后不该再通知");
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void aWriteJustBeforeTheDeadlineRestartsTheWait(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> debounced = base.debounce(input, 3);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> debounced.onDirty(() -> received.add(debounced.get())));
        input.set(1);
        base.advance(2);
        input.set(2);
        base.advance(2);

        assertEquals(List.of(), received, "第一次的截止 t0+3 已经被第二次推后");
        base.advance(1);

        assertEquals(List.of(2), received, "只在 t0+5 通知一次");
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void valueStaysAtTheLastEmissionWhileWaiting(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> debounced = base.debounce(input, 2);
        this.bindings.bind(() -> debounced.onDirty(() -> {
        }));
        input.set(1);
        base.advance(2);

        assertEquals(1, debounced.get());
        input.set(2);
        base.advance(1);

        assertEquals(2, input.get());
        assertEquals(1, debounced.get());
        base.advance(1);

        assertEquals(2, debounced.get());
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void withoutSubscribersItIsATransparentReadAndHoldsNoTask(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        AbstractSignal<Integer> internalInput = (AbstractSignal<Integer>) input;
        Signal<Integer> debounced = base.debounce(input, 3);
        AbstractSignal<Integer> internal = (AbstractSignal<Integer>) debounced;
        long before = internal.version();
        input.set(7);

        assertEquals(7, debounced.get(), "无订阅时读到的就是上游当前值");
        assertTrue(internal.version() > before, "拉取路径要推进版本, 下游才会重算");
        assertFalse(base.delayer().scheduled(), "无订阅时不该占调度任务");
        assertEquals(0, SignalTestAccess.entryCount(internalInput), "无订阅时不该挂在上游上");
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void subscribingDoesNotReplayChangesMadeBeforeIt(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> debounced = base.debounce(input, 2);
        input.set(5);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> debounced.onDirty(() -> received.add(debounced.get())));
        base.advance(10);

        assertEquals(List.of(), received, "订阅前的变化并进基线, 不补发");
        assertEquals(5, debounced.get(), "基线就是订阅那一刻的上游值");
        input.set(6);
        base.advance(1);

        assertEquals(List.of(), received);
        base.advance(1);

        assertEquals(List.of(6), received);
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void aSupersededTaskThatStillFiresEmitsNothing(TimeBase base) {
        base.delayer().ignoreCancel();
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> debounced = base.debounce(input, 3);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> debounced.onDirty(() -> received.add(debounced.get())));
        input.set(1);
        base.advance(1);
        input.set(2);

        assertEquals(2, base.delayer().pending(), "两个任务都还排着");
        base.advance(2);

        assertEquals(List.of(), received, "被顶掉的第一个任务到点了也不发");
        base.advance(1);

        assertEquals(List.of(2), received, "只有最后排入的那个任务发出");
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void aTaskLeftOverFromThePreviousActivationCannotShortCircuitTheNewDelay(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> debounced = base.debounce(input, 3);
        List<Integer> received = new ArrayList<>();
        Subscription first = debounced.onDirty(() -> received.add(debounced.get()));
        input.set(1);
        base.delayer().ignoreCancel();
        first.close();

        assertEquals(1, base.delayer().pending());
        this.bindings.bind(() -> input.onDirty(() -> base.advance(3)));
        this.bindings.bind(() -> debounced.onDirty(() -> received.add(debounced.get())));
        input.set(2);

        assertEquals(List.of(), received, "新一段的第一次变化必须等完整的 delay");
        base.advance(2);

        assertEquals(List.of(), received);
        base.advance(1);

        assertEquals(List.of(2), received, "由新一段自己排的任务发出");
    }

    @ParameterizedTest
    @EnumSource(TimeBase.class)
    void aFailedRescheduleKeepsTheEarlierTaskWhichStillEmitsTheLatestValue(TimeBase base) {
        MutableSignal<Integer> input = Signal.of(0);
        Signal<Integer> debounced = base.debounce(input, 3);
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> debounced.onDirty(() -> received.add(debounced.get())));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            input.set(1);
            base.advance(1);
            base.delayer().failNextSchedule(new IllegalStateException("scheduler rejected"));
            input.set(2);

            assertEquals(1, probe.failures().size());
            assertEquals(1, base.delayer().pending());
            base.advance(2);

            assertEquals(List.of(2), received, "留下的任务按原来的时刻发出, 值取最新的");
        }
    }
}
