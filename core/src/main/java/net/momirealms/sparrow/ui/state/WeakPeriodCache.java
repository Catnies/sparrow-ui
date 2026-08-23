package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;

// 按周期弱缓存时钟节点: 同周期共享一个实例, 没人持有的周期随 GC 消失, 死掉的槽在下次取用时清掉.
final class WeakPeriodCache<V> {
    private final Map<Long, Entry<V>> entries = new HashMap<>();      // 周期 -> 实例, 只弱持有
    private final ReferenceQueue<V> released = new ReferenceQueue<>();  // 实例已被回收的槽

    // 取该周期的实例, 没有或已被回收就用 create 建一个. 返回值由调用方强持有才能活下去.
    @NotNull
    synchronized V get(long period, @NotNull LongFunction<? extends V> create) {
        for (Reference<?> dead; (dead = this.released.poll()) != null; ) {
            // 只清仍指向这条死引用的槽, 不误删已经重建的实例
            this.entries.remove(((Entry<?>) dead).period, dead);
        }
        Entry<V> cached = this.entries.get(period);
        V value = cached == null ? null : cached.get();
        if (value == null) {
            value = create.apply(period);
            this.entries.put(period, new Entry<>(period, value, this.released));
        }
        return value;
    }

    // 当前还占着槽的周期数, 含已回收但还没清的.
    synchronized int size() {
        return this.entries.size();
    }

    synchronized void clear() {
        this.entries.clear();
    }

    // 对实例的弱引用, 携带所在周期, 回收后能直接从引用队列定位到槽.
    private static final class Entry<V> extends WeakReference<V> {
        private final long period;

        private Entry(long period, V value, ReferenceQueue<? super V> queue) {
            super(value, queue);
            this.period = period;
        }
    }
}
