package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;

import java.util.function.Function;

final class CombinedSignal<T> extends AbstractSignal<T> {
    private final AbstractSignal<?>[] sources;
    private final Function<Object[], ? extends T> combiner;
    private volatile Cached<T> cached;
    private Subscription[] upstream;

    CombinedSignal(AbstractSignal<?>[] sources, Function<Object[], ? extends T> combiner) {
        this.sources = sources;
        this.combiner = combiner;
    }

    @Override
    public T get() {
        long versionSum = this.versionSum();
        Cached<T> current = this.cached;
        if (current != null && current.versionSum() == versionSum) {
            return current.value();
        }
        Object[] values = new Object[this.sources.length];
        for (int i = 0; i < this.sources.length; i++) {
            values[i] = this.sources[i].get();
        }
        T value = this.combiner.apply(values);
        this.cached = new Cached<>(value, versionSum);
        return value;
    }

    @Override
    long version() {
        return this.versionSum();
    }

    // 来源固定且各自版本单调递增, 版本和不变即可确认所有来源都未失效
    private long versionSum() {
        long sum = 0L;
        for (int i = 0; i < this.sources.length; i++) {
            sum += this.sources[i].version();
        }
        return sum;
    }

    @Override
    protected void onActive() {
        // 弱订阅阻断来源到派生节点的保活路径
        this.upstream = this.linkAll(this.sources, this::notifyDirty);
    }

    @Override
    protected void onInactive() {
        for (int i = 0; i < this.upstream.length; i++) {
            this.upstream[i].close();
        }
        this.upstream = null;
    }

    private record Cached<V>(V value, long versionSum) {
    }
}
