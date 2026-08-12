package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;

import java.util.Objects;
import java.util.function.Function;

/**
 * 派生分支: 有下游订阅时, 上游失效立即重算判等, 值不变则吞掉失效.
 * 无下游订阅时不挂上游监听, 一切由拉取路径驱动, 行为退化为惰性 map.
 *
 * @param <S> 上游值类型
 * @param <T> 派生值类型
 */
final class MapDistinctSignal<S, T> extends AbstractSignal<T> {
    private final AbstractSignal<S> source;
    private final Function<? super S, ? extends T> mapper;
    private final Object recomputeLock = new Object();
    private volatile Cached<T> cached;
    private volatile long version;
    private long notifiedVersion;   // 已向下游通知过的版本
    private Subscription upstream;

    MapDistinctSignal(AbstractSignal<S> source, Function<? super S, ? extends T> mapper) {
        this.source = source;
        this.mapper = mapper;
    }

    @Override
    public T get() {
        long sourceVersion = this.source.version();
        Cached<T> current = this.cached;
        if (current != null && current.sourceVersion() == sourceVersion) {
            return current.value();
        }
        synchronized (this.recomputeLock) {
            this.recomputeLocked();
            return this.cached.value();
        }
    }

    @Override
    long version() {
        Cached<T> current = this.cached;
        if (current == null || current.sourceVersion() != this.source.version()) {
            synchronized (this.recomputeLock) {
                this.recomputeLocked();
            }
        }
        return this.version;
    }

    // 把缓存与版本推进到上游当前版本, 值发生变化时递增版本. 已是最新时无操作.
    private void recomputeLocked() {
        long sourceVersion = this.source.version();
        Cached<T> current = this.cached;
        if (current != null && current.sourceVersion() == sourceVersion) {
            return;
        }
        T value = this.mapper.apply(this.source.get());
        boolean changed = current == null || !Objects.equals(current.value(), value);
        this.cached = new Cached<>(value, sourceVersion);
        if (changed) {
            this.version++;
        }
    }

    @Override
    protected void onActive() {
        // 这里使用弱订阅, 不能让分区反过来钉住本节点.
        this.upstream = this.source.onDirtyWeak(this::onSourceDirty);
        try {
            synchronized (this.recomputeLock) {
                // 没有基线时首次订阅会把"从无到有"误判为值变化,
                // 手动将 notifiedVersion 对齐为当前版本.
                this.recomputeLocked();
                this.notifiedVersion = this.version;
            }
        } catch (RuntimeException | Error exception) {
            // mapper 抛出时撤销上游挂载, 让 register 的回滚留下干净现场.
            this.upstream.close();
            this.upstream = null;
            throw exception;
        }
    }

    private void onSourceDirty() {
        // 上游失效是本节点唯一的活动时机, 而派发只在截断放行时才发生. 若不在这里清理,
        // 一个罕有变化的节点(季节每年变四次)会为早已关闭的菜单长期保持激活, 每个 tick 都白重算.
        this.reapDeadEntries();
        boolean shouldNotify = false;
        synchronized (this.recomputeLock) {
            this.recomputeLocked();
            if (this.version > this.notifiedVersion) {
                this.notifiedVersion = this.version;
                shouldNotify = true;
            }
        }
        if (shouldNotify) {
            this.notifyDirty();
        }
    }

    @Override
    protected void onInactive() {
        this.upstream.close();
        this.upstream = null;
    }

    private record Cached<V>(V value, long sourceVersion) {
    }
}
