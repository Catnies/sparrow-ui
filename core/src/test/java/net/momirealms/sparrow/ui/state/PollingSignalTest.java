package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingSignalTest {

    private static final long PERIOD = 2;
    private static final long ONE_TICK_MILLIS = 50L;
    private final Bindings bindings = new Bindings();

    @BeforeEach
    void installTicker() {
        TickingTestSupport.install();
        TickingTestSupport.installMillis(200, 500);
    }

    @AfterEach
    void restoreTicker() {
        TickingTestSupport.restore();
    }

    @Test
    void loadsOncePerPeriodOnlyWhileSubscribed() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.polling(0, executor, loads::incrementAndGet, PERIOD);

        assertEquals(1, executor.drain(), "创建即调度首载");
        TickingTestSupport.advance(PERIOD * 3);

        assertEquals(0, executor.pending());
        assertFalse(TickingTestSupport.scheduled(), "无订阅时不该挂在 tick 源上");
        Subscription subscription = signal.onDirty(() -> {
        });

        assertEquals(0, executor.pending(), "刚装载完就订阅, 不补装载");
        TickingTestSupport.advance(PERIOD);

        assertEquals(1, executor.pending(), "每个周期一次");
        executor.drain();
        TickingTestSupport.advance(PERIOD);

        assertEquals(1, executor.drain());
        assertEquals(3, loads.get());
        subscription.close();
        TickingTestSupport.advance(PERIOD * 3);

        assertEquals(0, executor.pending(), "退订后停止");
        assertFalse(TickingTestSupport.scheduled());
    }

    @Test
    void reloadedValueReachesSubscribersAndEqualValuesStayQuiet() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger backing = new AtomicInteger(1);
        AsyncSignal<Integer> signal = Signal.polling(0, executor, backing::get, PERIOD);
        executor.drain();
        List<Integer> received = new ArrayList<>();
        this.bindings.bind(() -> signal.onDirty(() -> received.add(signal.get())));
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(List.of(), received, "装载出相同的值不通知");
        backing.set(2);
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(List.of(2), received);
    }

    @Test
    void subscribingAfterAStalePeriodReloadsAtOnce() throws InterruptedException {
        ManualExecutor executor = new ManualExecutor();
        AsyncSignal<Integer> signal = Signal.polling(0, executor, () -> 1, 1);
        executor.drain();
        Subscription fresh = signal.onDirty(() -> {
        });

        assertEquals(0, executor.pending());
        fresh.close();
        Thread.sleep(ONE_TICK_MILLIS + 10);
        this.bindings.bind(() -> signal.onDirty(() -> {
        }));

        assertEquals(1, executor.pending(), "订阅时发现数据过期就补装载");
        executor.drain();
    }

    @Test
    void subscribingWhileTheInitialLoadIsInFlightDoesNotDoubleLoad() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.polling(0, executor, loads::incrementAndGet, PERIOD);

        assertEquals(1, executor.pending(), "首载已调度");
        this.bindings.bind(() -> signal.onDirty(() -> {
        }));

        assertEquals(1, executor.pending(), "首载在飞, 订阅不叠加");
        executor.drain();

        assertEquals(1, loads.get());
    }

    @Test
    void aFailedLoadStillCountsAsCompletedForTheActivationRefresh() throws InterruptedException {
        ManualExecutor executor = new ManualExecutor();
        AsyncSignal<Integer> signal = Signal.polling(0, executor, () -> {
            throw new IllegalStateException("database down");
        }, 1);
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            Thread.sleep(ONE_TICK_MILLIS + 10);
            executor.drain();

            assertEquals(1, probe.failures().size());
            this.bindings.bind(() -> signal.onDirty(() -> {
            }));

            assertEquals(0, executor.pending(), "刚失败过就订阅, 不该再刷一次, 否则连续失败会刷屏");
        }
    }

    @Test
    void onlyPartitionsWithASubscribedHandlePoll() {
        ManualExecutor executor = new ManualExecutor();
        List<String> loaded = new ArrayList<>();
        KeyedSignal<String, Integer> signal = KeyedSignal.polling(0, executor, key -> {
            loaded.add(key);
            return loaded.size();
        }, PERIOD);
        signal.get("a");
        signal.get("b");
        executor.drain();

        assertEquals(List.of("a", "b"), loaded);
        Signal<Integer> handleB = signal.at("b");
        this.bindings.bind(() -> signal.at("a").onDirty(() -> {
        }));
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(List.of("a", "b", "a"), loaded, "只有 a 在轮询");
        assertEquals(2, handleB.get(), "b 还是首载那份");
        signal.remove("a");
        TickingTestSupport.advance(PERIOD);

        assertEquals(0, executor.pending(), "驱逐后停止轮询");
        signal.get("a");
        executor.drain();
        TickingTestSupport.advance(PERIOD);

        assertEquals(1, executor.pending(), "重建后继续轮询");
        executor.drain();
    }

    @Test
    void switchingAwayStopsPollingTheOldPartitionAtOnce() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> loaded = new ArrayList<>();
        KeyedSignal<Integer, String> pages = KeyedSignal.polling("", executor, page -> {
            loaded.add(page);
            return "page-" + page + "#" + loaded.size();
        }, PERIOD);
        MutableSignal<Integer> page = Signal.of(0);
        Signal<String> shown = Signals.switching(pages, page);
        this.bindings.bind(() -> shown.onDirty(() -> {
        }));
        shown.get();
        executor.drain();
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(List.of(0, 0), loaded, "当前页在轮询");
        page.set(1);
        shown.get();
        executor.drain();
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(List.of(0, 0, 1, 1), loaded, "换页后只有新页在轮询, 旧页当场停");
    }

    @Test
    void deadSubscribersAreSweptOnThePollTickEvenWhenTheValueNeverChanges() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.polling(0, executor, () -> {
            loads.incrementAndGet();
            return 42;
        }, PERIOD);
        executor.drain();
        WeakReference<?> probe = subscribeAndDrop(signal);
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(2, loads.get(), "有订阅时在轮询");
        GcSupport.awaitCollected(probe);
        TickingTestSupport.advance(PERIOD);
        executor.drain();
        TickingTestSupport.advance(PERIOD * 2);

        assertEquals(0, executor.pending(), "常量数据源也得停: 清扫发生在轮询拍, 与有没有失效无关");
        assertFalse(TickingTestSupport.scheduled());
        assertEquals(2, loads.get(), "清扫发生在装载之前, 下游死后连一次多余的装载都不该有");
    }

    private static WeakReference<?> subscribeAndDrop(Signal<?> signal) {
        Object captured = new Object();
        signal.onDirty(captured::hashCode);
        return new WeakReference<>(captured);
    }

    @Test
    void aHeldDerivedNodeWhoseSubscribersDiedStopsThePollToo() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.polling(0, executor, () -> {
            loads.incrementAndGet();
            return 42;
        }, PERIOD);
        executor.drain();
        Signal<Integer> held = signal.map(Function.identity());
        WeakReference<?> probe = subscribeAndDrop(held);
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(2, loads.get(), "派生节点有订阅时在轮询");
        GcSupport.awaitCollected(probe);
        TickingTestSupport.advance(PERIOD);
        executor.drain();
        TickingTestSupport.advance(PERIOD * 2);

        assertEquals(0, executor.pending(), "派生节点还被持有也得停: 它的订阅者已经死光");
        assertFalse(TickingTestSupport.scheduled());
        assertEquals(2, loads.get(), "清扫发生在装载之前, 下游死后连一次多余的装载都不该有");
        assertEquals(0, TickingTestSupport.entryCountOf(held), "派生节点清到空");
        assertEquals(0, TickingTestSupport.entryCountOf(signal), "派生节点清到空就从轮询源上退订");
    }

    @Test
    void theSweepWalksTheWholeDerivedChain() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.polling(0, executor, () -> {
            loads.incrementAndGet();
            return 42;
        }, PERIOD);
        executor.drain();
        Signal<Integer> first = signal.map(Function.identity());
        Signal<Integer> second = first.mapDistinct(Function.identity());
        Signal<Integer> third = Signals.combine(second, Signal.of(1), (value, one) -> value + one);
        WeakReference<?> probe = subscribeAndDrop(third);
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(2, loads.get());
        GcSupport.awaitCollected(probe);
        TickingTestSupport.advance(PERIOD);
        executor.drain();
        TickingTestSupport.advance(PERIOD * 2);

        assertEquals(2, loads.get(), "末端死后整条链都该退订, 轮询停表");
        assertFalse(TickingTestSupport.scheduled());
        assertEquals(0, TickingTestSupport.entryCountOf(third));
        assertEquals(0, TickingTestSupport.entryCountOf(second));
        assertEquals(0, TickingTestSupport.entryCountOf(first));
        assertEquals(0, TickingTestSupport.entryCountOf(signal));
    }

    @Test
    void aHeldPartitionHandleWhoseSubscribersDiedStopsThePollToo() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<String, Integer> keyed = KeyedSignal.polling(0, executor, key -> {
            loads.incrementAndGet();
            return 0;
        }, PERIOD);
        Signal<Integer> handle = keyed.at("k");
        WeakReference<?> probe = subscribeAndDrop(handle);
        handle.get();
        executor.drain();
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(2, loads.get(), "句柄有订阅, 分区在轮询");
        GcSupport.awaitCollected(probe);
        TickingTestSupport.advance(PERIOD);
        executor.drain();
        TickingTestSupport.advance(PERIOD * 2);

        assertEquals(2, loads.get(), "句柄还被持有也得停: 它的订阅者已经死光");
        assertFalse(TickingTestSupport.scheduled());
        assertEquals(0, TickingTestSupport.entryCountOf(handle));
    }

    @Test
    void aHeldSwitchingNodeWhoseSubscribersDiedStopsThePollToo() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        KeyedSignal<Integer, Integer> pages = KeyedSignal.polling(0, executor, page -> {
            loads.incrementAndGet();
            return 0;
        }, PERIOD);
        MutableSignal<Integer> page = Signal.of(0);
        Signal<Integer> shown = Signals.switching(pages, page);
        WeakReference<?> probe = subscribeAndDrop(shown);
        shown.get();
        executor.drain();
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(2, loads.get());
        GcSupport.awaitCollected(probe);
        TickingTestSupport.advance(PERIOD);
        executor.drain();
        TickingTestSupport.advance(PERIOD * 2);

        assertEquals(2, loads.get(), "切换节点还被持有也得停: 它的订阅者已经死光");
        assertFalse(TickingTestSupport.scheduled());
        assertEquals(0, TickingTestSupport.entryCountOf(shown));
    }

    @Test
    void theSweepNeverNotifiesLiveSubscribersOfAnUnchangedValue() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.polling(0, executor, () -> {
            loads.incrementAndGet();
            return 42;
        }, PERIOD);
        executor.drain();
        Signal<Integer> held = signal.map(Function.identity());
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> held.onDirty(notifications::incrementAndGet));
        WeakReference<?> probe = subscribeAndDrop(held);
        GcSupport.awaitCollected(probe);
        for (int round = 0; round < 3; round++) {
            TickingTestSupport.advance(PERIOD);
            executor.drain();
        }

        assertEquals(4, loads.get(), "活着的订阅者让轮询继续");
        assertEquals(1, TickingTestSupport.entryCountOf(held), "死条目清掉, 活条目留下");
        assertEquals(0, notifications.get(), "恒等装载与清扫都不是用户可见的失效");
    }

    @Test
    void aPollCallbackLeftOverFromThePreviousActivationSubmitsNothing() {
        ManualExecutor executor = new ManualExecutor();
        AsyncSignal<Integer> signal = Signal.polling(0, executor, () -> 1, PERIOD);
        executor.drain();
        Subscription first = signal.onDirty(() -> {
        });
        Runnable stalePoll = TickingTestSupport.subscriberCallbacksOf(Signals.everyTicks(PERIOD)).get(0);
        first.close();
        this.bindings.bind(() -> signal.onDirty(() -> {
        }));
        stalePoll.run();

        assertEquals(0, executor.pending(), "上一段的轮询回调迟到, 不该为新一段多提交一次装载");
        TickingTestSupport.advance(PERIOD);

        assertEquals(1, executor.pending(), "新一段自己的轮询拍照常");
        executor.drain();
    }

    @Test
    void aRejectedPollIsReportedAndTheNextTickRetries() {
        ManualExecutor delegate = new ManualExecutor();
        AtomicInteger rejections = new AtomicInteger();
        Executor flaky = command -> {
            if (rejections.getAndDecrement() > 0) {
                throw new RejectedExecutionException("busy");
            }
            delegate.execute(command);
        };
        AtomicInteger loads = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.polling(0, flaky, loads::incrementAndGet, PERIOD);
        delegate.drain();
        this.bindings.bind(() -> signal.onDirty(() -> {
        }));
        try (ExceptionHandlerProbe probe = new ExceptionHandlerProbe()) {
            rejections.set(1);
            TickingTestSupport.advance(PERIOD);

            assertEquals(1, probe.failures().size(), "拒绝上报给统一异常处理器");
            assertEquals(0, delegate.pending());
            TickingTestSupport.advance(PERIOD);

            assertEquals(1, delegate.drain(), "下一拍照常再试");
            assertEquals(2, loads.get());
        }
    }

    @Test
    void nonPositivePeriodsAreRejectedUpFront() {
        ManualExecutor executor = new ManualExecutor();
        Supplier<Integer> loader = () -> 1;
        Function<String, Integer> keyedLoader = key -> 1;

        assertThrows(IllegalArgumentException.class, () -> Signal.polling(0, executor, loader, 0));
        assertThrows(IllegalArgumentException.class, () -> Signal.polling(0, executor, loader, -1, Integer::equals));
        assertThrows(IllegalArgumentException.class, () -> KeyedSignal.polling(0, executor, keyedLoader, 0));
        assertThrows(IllegalArgumentException.class, () -> KeyedSignal.polling(0, executor, keyedLoader, 0, Integer::equals));
        assertThrows(IllegalArgumentException.class, () -> Signal.pollingMillis(0, executor, loader, 49));
        assertThrows(IllegalArgumentException.class, () -> KeyedSignal.pollingMillis(0, executor, keyedLoader, 0, Integer::equals));
        assertEquals(0, executor.pending(), "参数不合法时不该已经调度了首载");
    }

    @Test
    void millisPollingFollowsTheMillisClock() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncSignal<Integer> signal = Signal.pollingMillis(0, executor, loads::incrementAndGet, 200);
        executor.drain();

        assertFalse(TickingTestSupport.millisScheduled(200), "无订阅时毫秒时钟不起任务");
        this.bindings.bind(() -> signal.onDirty(() -> {
        }));

        assertTrue(TickingTestSupport.millisScheduled(200), "第一个订阅者到来才起任务");
        TickingTestSupport.advanceMillis(200);

        assertEquals(1, executor.drain(), "每拍一次装载");
        TickingTestSupport.advance(PERIOD * 5);

        assertEquals(0, executor.pending(), "tick 时钟与它无关");
        assertEquals(2, loads.get());
    }

    @Test
    void millisPollingOnAKeyedSourceSharesOneClockPerPeriod() {
        ManualExecutor executor = new ManualExecutor();
        List<String> loaded = new ArrayList<>();
        KeyedSignal<String, Integer> signal = KeyedSignal.pollingMillis(0, executor, key -> {
            loaded.add(key);
            return loaded.size();
        }, 500);
        this.bindings.bind(() -> signal.at("a").onDirty(() -> {
        }));
        this.bindings.bind(() -> signal.at("b").onDirty(() -> {
        }));
        signal.get("a");
        signal.get("b");
        executor.drain();
        TickingTestSupport.advanceMillis(500);
        executor.drain();

        assertEquals(List.of("a", "b", "a", "b"), loaded, "两个分区挂在同一个 500 毫秒时钟上, 同一拍一起装载");
        assertEquals(1, TickingTestSupport.millisStarts(500), "同周期只起一个调度任务");
    }

    @Test
    void factoriesMirrorAcrossTheThreeSources() throws NoSuchMethodException {
        assertEquals(AsyncSignal.class, factory(Signal.class, "polling", Object.class, Executor.class, Supplier.class, long.class).getReturnType());
        assertEquals(AsyncSignal.class, factory(Signal.class, "polling", Object.class, Executor.class, Supplier.class, long.class, BiPredicate.class).getReturnType());
        assertEquals(KeyedSignal.class, factory(KeyedSignal.class, "polling", Object.class, Executor.class, Function.class, long.class).getReturnType());
        assertEquals(KeyedSignal.class, factory(KeyedSignal.class, "polling", Object.class, Executor.class, Function.class, long.class, BiPredicate.class).getReturnType());
        assertEquals(PlayerKeyedSignal.class, factory(PlayerKeyedSignal.class, "polling", Object.class, Executor.class, Function.class, long.class).getReturnType());
        assertEquals(PlayerKeyedSignal.class, factory(PlayerKeyedSignal.class, "polling", Object.class, Executor.class, Function.class, long.class, BiPredicate.class).getReturnType());
        assertEquals(AsyncSignal.class, factory(Signal.class, "pollingMillis", Object.class, Executor.class, Supplier.class, long.class).getReturnType());
        assertEquals(AsyncSignal.class, factory(Signal.class, "pollingMillis", Object.class, Executor.class, Supplier.class, long.class, BiPredicate.class).getReturnType());
        assertEquals(KeyedSignal.class, factory(KeyedSignal.class, "pollingMillis", Object.class, Executor.class, Function.class, long.class).getReturnType());
        assertEquals(KeyedSignal.class, factory(KeyedSignal.class, "pollingMillis", Object.class, Executor.class, Function.class, long.class, BiPredicate.class).getReturnType());
        assertEquals(PlayerKeyedSignal.class, factory(PlayerKeyedSignal.class, "pollingMillis", Object.class, Executor.class, Function.class, long.class).getReturnType());
        assertEquals(PlayerKeyedSignal.class, factory(PlayerKeyedSignal.class, "pollingMillis", Object.class, Executor.class, Function.class, long.class, BiPredicate.class).getReturnType());
    }

    private static Method factory(Class<?> owner, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return owner.getMethod(name, parameterTypes);
    }

    @Test
    void customEqualityAppliesToPolledValues() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger backing = new AtomicInteger(10);
        AsyncSignal<Integer> signal = Signal.polling(0, executor, backing::get, PERIOD, (a, b) -> a / 10 == b / 10);
        executor.drain();
        AtomicInteger notifications = new AtomicInteger();
        this.bindings.bind(() -> signal.onDirty(notifications::incrementAndGet));
        backing.set(11);
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(0, notifications.get());
        backing.set(20);
        TickingTestSupport.advance(PERIOD);
        executor.drain();

        assertEquals(1, notifications.get());
    }
}
