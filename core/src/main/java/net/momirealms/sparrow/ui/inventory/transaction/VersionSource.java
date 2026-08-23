package net.momirealms.sparrow.ui.inventory.transaction;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

// 版本以系统毫秒为下界并严格递增, 只用于当前类加载器生命周期内比较新旧.
final class VersionSource {
    private final LongSupplier clock;
    private final AtomicLong lastVersion = new AtomicLong();

    VersionSource(@NotNull LongSupplier clock) {
        this.clock = clock;
    }

    long next() {
        long currentTime = this.clock.getAsLong();
        return this.lastVersion.updateAndGet(previous -> Math.max(currentTime, previous + 1));
    }
}
