package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.provider.Arguments;
import java.lang.reflect.Field;
import java.util.stream.Stream;

public final class DelayTestSupport {

    private static ManualDelayer ticks;
    private static ManualDelayer millis;
    private static boolean installed;
    private DelayTestSupport() {
    }

    public static synchronized void install() {
        ticks = new ManualDelayer();
        millis = new ManualDelayer();
        setDelayer("tickDelayer", ticks);
        setDelayer("millisDelayer", millis);
        installed = true;
    }

    public static synchronized void restore() {
        if (!installed) {
            return;
        }
        setDelayer("tickDelayer", Delayer.paperTicks());
        setDelayer("millisDelayer", Delayer.paperMillis());
        ticks = null;
        millis = null;
        installed = false;
    }

    @NotNull
    public static ManualDelayer ticks() {
        return ticks;
    }

    @NotNull
    public static ManualDelayer millis() {
        return millis;
    }

    private static void setDelayer(String fieldName, Delayer delayer) {
        try {
            Field field = Signals.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, delayer);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to install the manual delayer", exception);
        }
    }

    public enum TimeBase {
        TICKS {
            @Override
            <T> Signal<T> debounce(Signal<T> source, long delay) {
                return source.debounce(delay);
            }
            @Override
            <T> Signal<T> throttle(Signal<T> source, long delay) {
                return source.throttle(delay);
            }
            @Override
            ManualDelayer delayer() {
                return ticks();
            }
        },
        MILLIS {
            @Override
            <T> Signal<T> debounce(Signal<T> source, long delay) {
                return source.debounceMillis(delay);
            }
            @Override
            <T> Signal<T> throttle(Signal<T> source, long delay) {
                return source.throttleMillis(delay);
            }
            @Override
            ManualDelayer delayer() {
                return millis();
            }
        };
        abstract <T> Signal<T> debounce(Signal<T> source, long delay);
        abstract <T> Signal<T> throttle(Signal<T> source, long delay);
        abstract ManualDelayer delayer();
        void advance(long units) {
            this.delayer().advance(units);
        }
    }

    public enum Pacing {
        DEBOUNCE {
            @Override
            <T> Signal<T> apply(TimeBase base, Signal<T> source, long delay) {
                return base.debounce(source, delay);
            }
        },
        THROTTLE {
            @Override
            <T> Signal<T> apply(TimeBase base, Signal<T> source, long delay) {
                return base.throttle(source, delay);
            }
        };
        abstract <T> Signal<T> apply(TimeBase base, Signal<T> source, long delay);
    }

    @NotNull
    public static Stream<Arguments> combinations() {
        return Stream.of(TimeBase.values())
                .flatMap(base -> Stream.of(Pacing.values()).map(pacing -> Arguments.of(base, pacing)));
    }
}
