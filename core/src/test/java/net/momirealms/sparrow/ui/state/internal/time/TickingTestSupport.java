package net.momirealms.sparrow.ui.state.internal.time;

import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.SignalTestAccess;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

public final class TickingTestSupport {

    private static final AtomicLong CURRENT_TICK = new AtomicLong();
    private static LongConsumer callback;
    private static boolean installed;
    private static final Map<Long, ManualMillisTicker> MILLIS = new HashMap<>();
    private static final List<TickingSignal> PINNED = new ArrayList<>();
    private static boolean millisInstalled;
    private TickingTestSupport() {
    }

    public static synchronized void install() {
        CURRENT_TICK.set(0L);
        callback = null;
        setTicking(new TickingSignal(onTick -> {
            long epochStart = CURRENT_TICK.get();
            callback = tick -> onTick.accept(tick - epochStart);
            return () -> callback = null;
        }));
        installed = true;
    }

    public static synchronized void installFailing(@NotNull RuntimeException failure) {
        CURRENT_TICK.set(0L);
        callback = null;
        setTicking(new TickingSignal(ignoredOnTick -> {
            throw failure;
        }));
        installed = true;
    }

    public static synchronized void installMillis(long... periods) {
        clearMillisClocks();
        MILLIS.clear();
        PINNED.clear();
        WeakPeriodCache<TickingSignal> cache = millisClocks();
        for (int i = 0; i < periods.length; i++) {
            ManualMillisTicker ticker = new ManualMillisTicker();
            MILLIS.put(periods[i], ticker);
            PINNED.add(cache.get(periods[i], ignoredPeriod -> new TickingSignal(ticker)));
        }
        millisInstalled = true;
    }

    public static synchronized void restore() {
        if (installed) {
            setTicking(null);
            callback = null;
            installed = false;
        }
        if (millisInstalled) {
            clearMillisClocks();
            MILLIS.clear();
            PINNED.clear();
            millisInstalled = false;
        }
    }

    public static void advanceMillis(long periodMillis) {
        ManualMillisTicker ticker;
        synchronized (TickingTestSupport.class) {
            ticker = MILLIS.get(periodMillis);
        }
        if (ticker != null) {
            ticker.tick();
        }
    }

    public static synchronized boolean millisScheduled(long periodMillis) {
        ManualMillisTicker ticker = MILLIS.get(periodMillis);
        return ticker != null && ticker.callback != null;
    }

    public static synchronized int millisStarts(long periodMillis) {
        ManualMillisTicker ticker = MILLIS.get(periodMillis);
        return ticker == null ? 0 : ticker.starts.get();
    }

    public static void advance(long count) {
        for (long index = 0; index < count; index++) {
            long tick = CURRENT_TICK.incrementAndGet();
            LongConsumer target = callback;
            if (target != null) {
                target.accept(tick);
            }
        }
    }

    public static boolean scheduled() {
        return callback != null;
    }

    public static int entryCountOf(@NotNull Signal<?> signal) {
        return SignalTestAccess.entryCount(signal);
    }

    @NotNull
    public static List<Runnable> subscriberCallbacksOf(@NotNull Signal<?> signal) {
        try {
            Field entriesField = AbstractSignal.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            List<Runnable> callbacks = new ArrayList<>();
            for (Object entry : (List<?>) entriesField.get(signal)) {
                Field nodeField = entry.getClass().getDeclaredField("node");
                nodeField.setAccessible(true);
                Object node = ((java.lang.ref.Reference<?>) nodeField.get(entry)).get();
                if (node == null) continue;
                Field callbackField = node.getClass().getDeclaredField("callback");
                callbackField.setAccessible(true);
                Runnable callback = (Runnable) callbackField.get(node);
                if (callback != null) callbacks.add(callback);
            }
            return callbacks;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to reach the subscriber callbacks", exception);
        }
    }

    private static void setTicking(TickingSignal signal) {
        try {
            Field field = Signals.class.getDeclaredField("ticking");
            field.setAccessible(true);
            field.set(null, signal);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to install the manual ticking signal", exception);
        }
    }

    private static void clearMillisClocks() {
        millisClocks().clear();
    }

    @SuppressWarnings("unchecked")
    private static WeakPeriodCache<TickingSignal> millisClocks() {
        try {
            Field field = Signals.class.getDeclaredField("millisClocks");
            field.setAccessible(true);
            return (WeakPeriodCache<TickingSignal>) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to reach the cached millis clocks", exception);
        }
    }

    private static final class ManualMillisTicker implements TickingSignal.Ticker {
        private final AtomicInteger starts = new AtomicInteger();
        private volatile LongConsumer callback;
        private long elapsed;
        @Override
        @NotNull
        public Handle start(@NotNull LongConsumer onTick) {
            this.starts.incrementAndGet();
            this.elapsed = 0L;
            this.callback = onTick;
            return () -> this.callback = null;
        }
        private void tick() {
            LongConsumer target = this.callback;
            if (target != null) {
                target.accept(++this.elapsed);
            }
        }
    }
}
