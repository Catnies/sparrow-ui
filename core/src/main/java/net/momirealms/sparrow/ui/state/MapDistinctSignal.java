package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;

sealed class MapDistinctSignal<S, T> extends AbstractSignal<T> permits LensSignal {
    private final AbstractSignal<S> source;
    private final Function<? super S, ? extends T> mapper;
    private final BiPredicate<? super T, ? super T> sameValue;
    private final AtomicReference<Cached<T>> cached = new AtomicReference<>();
    private final AtomicLong notifiedVersion = new AtomicLong();   // 已向下游通知过的版本
    private Subscription upstream;

    MapDistinctSignal(AbstractSignal<S> source, Function<? super S, ? extends T> mapper, BiPredicate<? super T, ? super T> sameValue) {
        this.source = source;
        this.mapper = mapper;
        this.sameValue = sameValue;
    }

    @Override
    public T get() {
        return this.align().value();
    }

    @Override
    long version() {
        return this.align().version();
    }

    /**
     * 把缓存推进到上游当前版本, 已经是最新时直接返回.
     * <p>求值不持锁, {@code mapper} 可以读取其他 signal. 多线程刷新可能重复计算同一个上游版本,
     * 只有 CAS 成功的结果会发布, 因此 {@code mapper} 必须是纯函数.
     *
     * @return 与上游当前版本对齐的缓存记录
     */
    private Cached<T> align() {
        while (true) {
            long sourceVersion = this.source.version();
            @Nullable Cached<T> current = this.cached.get();
            if (current != null && current.sourceVersion() == sourceVersion) {
                return current;
            }
            T value = this.mapper.apply(this.source.get());
            boolean changed = current == null || !same(this.sameValue, current.value(), value);
            long version = current == null ? 1L : current.version() + (changed ? 1L : 0L);
            Cached<T> next = new Cached<>(value, sourceVersion, version);
            // 只从当前读到的记录前进, CAS 失败后重读
            if (this.cached.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    @Override
    protected void onActive() {
        this.upstream = this.linkTo(this.source, this::onSourceDirty);
        try {
            // 首次订阅先建立通知基线, 不把首次求值当成变化
            this.notifiedVersion.accumulateAndGet(this.align().version(), Math::max);
        } catch (RuntimeException | Error exception) {
            // 基线求值失败时撤销上游订阅, 配合 register 回滚
            this.upstream.close();
            this.upstream = null;
            throw exception;
        }
    }

    private void onSourceDirty() {
        long version = this.align().version();
        // 线程推进 notifiedVersion 成功后执行派发, 同一版本只通知一次
        while (true) {
            long notified = this.notifiedVersion.get();
            if (version <= notified) {
                return;
            }
            if (this.notifiedVersion.compareAndSet(notified, version)) {
                this.notifyDirty();
                return;
            }
        }
    }

    @Override
    protected void onInactive() {
        this.upstream.close();
        this.upstream = null;
    }

    private record Cached<V>(V value, long sourceVersion, long version) {
    }
}
