package net.momirealms.sparrow.ui.inventory.transaction;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

// 给成功的事务发版本号, 以系统毫秒时间打底, 在当前 JVM / 类加载器生命周期内严格单调.
// 同一毫秒里的多笔事务和系统时钟回拨都退化成"前一个加一", 所以版本只适合比新旧, 既不等于精确墙上时间, 也不跨重启延续.
final class VersionSource {
    private final LongSupplier clock;                        // 提供当前系统毫秒时间, 测试可以换成可控时钟
    private final AtomicLong lastVersion = new AtomicLong(); // 已签发的最大版本

    VersionSource(@NotNull LongSupplier clock) {
        this.clock = clock;
    }

    // 签发一个严格大于此前所有结果的版本.
    long next() {
        long currentTime = this.clock.getAsLong();
        return this.lastVersion.updateAndGet(previous -> Math.max(currentTime, previous + 1));
    }
}
