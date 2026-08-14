package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;

import java.util.function.Function;

/**
 * 多来源组合节点: 任一来源失效即失效, 值在拉取时以全部来源的快照重算.
 *
 * @param <T> 组合值类型
 */
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

    private long versionSum() {
        long sum = 0L;
        for (int i = 0; i < this.sources.length; i++) {
            sum += this.sources[i].version();
        }
        return sum;
    }

    @Override
    protected void onActive() {
        // 这里使用弱订阅, 不能让来源反过来钉住本节点.
        this.upstream = attachAll(this.sources, this::notifyDirty);
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
