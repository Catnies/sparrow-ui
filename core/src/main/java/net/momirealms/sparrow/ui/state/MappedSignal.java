package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;

import java.util.function.Function;

/**
 * 惰性派生节点, 失效原样透传, 值在拉取时按上游版本重算并缓存.
 *
 * @param <S> 上游值类型
 * @param <T> 派生值类型
 */
final class MappedSignal<S, T> extends AbstractSignal<T> {
    private final AbstractSignal<S> source;
    private final Function<? super S, ? extends T> mapper;
    private volatile Cached<T> cached;
    private Subscription upstream;

    MappedSignal(AbstractSignal<S> source, Function<? super S, ? extends T> mapper) {
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
        T value = this.mapper.apply(this.source.get());
        this.cached = new Cached<>(value, sourceVersion);
        return value;
    }

    @Override
    long version() {
        return this.source.version();
    }

    @Override
    protected void onActive() {
        this.upstream = this.source.link(this, this::notifyDirty);
    }

    @Override
    protected void onInactive() {
        this.upstream.close();
        this.upstream = null;
    }

    private record Cached<V>(V value, long sourceVersion) {
    }
}
