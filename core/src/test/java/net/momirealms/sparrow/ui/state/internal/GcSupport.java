package net.momirealms.sparrow.ui.state.internal;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertNull;

public final class GcSupport {

    private static final int MAX_ATTEMPTS = 100;
    private static final int PRESSURE_ROUNDS = 10;
    private static final long PAUSE_MILLIS = 10L;
    private static final long ENQUEUE_SETTLE_MILLIS = 50L;
    private GcSupport() {}

    public static void awaitCollected(WeakReference<?> probe) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS && probe.get() != null; attempt++) {
            System.gc();
            sleep(PAUSE_MILLIS);
        }

        assertNull(probe.get(), "被观察的对象应已被回收");
        sleep(ENQUEUE_SETTLE_MILLIS);
    }

    public static void pressure() {
        for (int attempt = 0; attempt < PRESSURE_ROUNDS; attempt++) {
            System.gc();
            sleep(PAUSE_MILLIS);
        }
        sleep(ENQUEUE_SETTLE_MILLIS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
