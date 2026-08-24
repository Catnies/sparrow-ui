package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * 派生节点, 有下游订阅时, 上游失效立即重算判等, 值不变则吞掉失效.
 * 无下游订阅时不挂上游监听, 一切由拉取路径驱动, 行为退化为惰性 map.
 *
 * @param <S> 上游值类型
 * @param <T> 派生值类型
 */
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
     * <p>求值不持锁, 所以 {@code mapper} 可以读取任何 signal.
     * 多个线程同时刷新时它可能为同一个上游版本跑不止一次, 而只有 CAS 成功的那一份结果会被发布, 所以它必须是纯函数.
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
            // 只从读到的那一份往前推, 输给别人就重来, 缓存因此只会前进
            if (this.cached.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    @Override
    protected void onActive() {
        this.upstream = this.linkTo(this.source, this::onSourceDirty);
        try {
            // 没有基线时首次订阅会把"从无到有"误判为值变化, 把 notifiedVersion 抬到当前版本.
            this.notifiedVersion.accumulateAndGet(this.align().version(), Math::max);
        } catch (RuntimeException | Error exception) {
            // mapper 抛出时撤销上游挂载, 让 register 的回滚留下干净现场.
            this.upstream.close();
            this.upstream = null;
            throw exception;
        }
    }

    private void onSourceDirty() {
        // 上游失效是本节点唯一的活动时机, 而派发只在截断放行时才发生.
        this.reapDeadEntries();
        long version = this.align().version();
        // 把 notifiedVersion 推过这个版本的那个线程负责派发, 别的线程直接走人, 一次变化只通知一遍
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
