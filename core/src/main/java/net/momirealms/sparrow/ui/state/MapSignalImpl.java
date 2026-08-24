package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * {@link MapSignal} 的实现, 每个方法代理给 delegate, 变更落地后推进版本与通知, 钩子也在这里调.
 * <p>三个视图与 {@code Map.Entry} 都写穿到本对象, 所以从视图上删、经 {@code setValue} 改同样通知.
 *
 * @param <K> key 类型
 * @param <V> 值类型
 */
final class MapSignalImpl<K, V> extends CollectionSignal<Map<K, V>> implements MapSignal<K, V> {
    private final Map<K, V> delegate;
    @Nullable private volatile BiFunction<K, V, V> putting;     // 存入之前的钩子, 挂多个时已串成一个
    @Nullable private volatile BiConsumer<K, V> removing;       // 移除之后的钩子, 同上

    MapSignalImpl(Map<K, V> delegate) {
        this.delegate = delegate;
    }

    @Override
    @NotNull
    public MapSignal<K, V> beforePut(@NotNull BiFunction<? super K, ? super V, ? extends V> hook) {
        Objects.requireNonNull(hook, "hook");
        BiFunction<K, V, V> current = this.putting;
        this.putting = current == null ? hook::apply : (key, value) -> hook.apply(key, current.apply(key, value));
        return this;
    }

    @Override
    @NotNull
    public MapSignal<K, V> afterRemove(@NotNull BiConsumer<? super K, ? super V> hook) {
        Objects.requireNonNull(hook, "hook");
        BiConsumer<K, V> current = this.removing;
        this.removing = current == null ? hook::accept : current.andThen(hook);
        return this;
    }

    @Override
    public Map<K, V> get() {
        return this;
    }

    // 放入之前过钩子, 返回真正存进去的值.
    private V putting(K key, V value) {
        BiFunction<K, V, V> hook = this.putting;
        return hook == null ? value : hook.apply(key, value);
    }

    private boolean hooked() {
        return this.putting != null || this.removing != null;
    }

    // 读取全部直接代理

    @Override
    public int size() {
        return this.delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return this.delegate.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return this.delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return this.delegate.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return this.delegate.get(key);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return this.delegate.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        this.delegate.forEach(action);
    }

    // 放入与替换

    @Override
    public V put(K key, V value) {
        return this.putOne(key, value);
    }

    // 放一个映射, 返回旧值; 有效变更才通知. 有钩子时先读旧值, 先摘旧再放新.
    private V putOne(K key, V value) {
        if (!this.hooked()) {
            V old = this.delegate.put(key, value);
            // 旧值为 null 时分不清 "原来没有" 与 "原来映射到 null", 一律按变了算
            if (old == null || !old.equals(value)) this.changed();
            return old;
        }
        V old = this.delegate.get(key);
        if (old != null && old.equals(value)) return old;
        if (old != null) this.removed(key, old);
        this.delegate.put(key, this.putting(key, value));
        this.changed();
        return old;
    }

    @Override
    public void putAll(@NotNull Map<? extends K, ? extends V> m) {
        if (m.isEmpty()) return;
        if (!this.hooked()) {
            this.delegate.putAll(m);
            this.changed();
            return;
        }
        // 逐个走放入路径, 每个值各自过钩子; 通知合并成一次
        this.batch(() -> {
            for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
                this.putOne(entry.getKey(), entry.getValue());
            }
        });
    }

    @Override
    public V putIfAbsent(K key, V value) {
        if (this.putting == null) {
            V old = this.delegate.putIfAbsent(key, value);
            if (old == null) this.changed();
            return old;
        }
        V existing = this.delegate.get(key);
        if (existing != null) return existing;
        V old = this.delegate.putIfAbsent(key, this.putting(key, value));
        if (old == null) this.changed();
        return old;
    }

    @Override
    public V replace(K key, V value) {
        if (!this.hooked()) {
            V old = this.delegate.replace(key, value);
            if (old != null && !old.equals(value)) this.changed();
            return old;
        }
        V old = this.delegate.get(key);
        if (old == null || old.equals(value)) return old;
        this.removed(key, old);
        this.delegate.replace(key, this.putting(key, value));
        this.changed();
        return old;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        if (!this.hooked()) {
            boolean replaced = this.delegate.replace(key, oldValue, newValue);
            if (replaced && !Objects.equals(oldValue, newValue)) this.changed();
            return replaced;
        }
        V current = this.delegate.get(key);
        if (current == null || !current.equals(oldValue)) return false;
        if (current.equals(newValue)) return true;
        this.removed(key, current);
        boolean replaced = this.delegate.replace(key, current, this.putting(key, newValue));
        if (replaced) this.changed();
        return replaced;
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if (this.delegate.isEmpty()) return;
        boolean[] changed = new boolean[1];
        this.delegate.replaceAll((key, old) -> {
            V replacement = function.apply(key, old);
            if (replacement == old) return old;
            changed[0] = true;
            return this.swapping(key, old, replacement);
        });
        if (changed[0]) this.changed();
    }

    // 替换一个映射的值时过钩子: 先摘旧再放新, 返回真正存进去的新值. 跑在 delegate 的重算函数里.
    private V swapping(K key, @Nullable V old, V replacement) {
        if (old != null) this.removed(key, old);
        return replacement == null ? null : this.putting(key, replacement);
    }

    private void removed(K key, V value) {
        BiConsumer<K, V> hook = this.removing;
        if (hook != null) hook.accept(key, value);
    }

    // compute 钩子跑在 delegate 的重算函数里, 并发 map 的原子性才保得住; 重算函数可能被重跑, 以最后一次为准.

    @Override
    public V compute(K key, @NotNull BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        boolean[] changed = new boolean[1];
        V result = this.delegate.compute(key, (k, old) -> {
            V replacement = remappingFunction.apply(k, old);
            if (replacement == old) return old;
            changed[0] = true;
            return this.swapping(k, old, replacement);
        });
        if (changed[0]) this.changed();
        return result;
    }

    @Override
    public V computeIfAbsent(K key, @NotNull Function<? super K, ? extends V> mappingFunction) {
        boolean[] changed = new boolean[1];
        V result = this.delegate.computeIfAbsent(key, k -> {
            V value = mappingFunction.apply(k);
            if (value == null) return null;
            V stored = this.putting(k, value);
            if (stored == null) return null;
            changed[0] = true;
            return stored;
        });
        if (changed[0]) this.changed();
        return result;
    }

    @Override
    public V computeIfPresent(K key, @NotNull BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        boolean[] changed = new boolean[1];
        V result = this.delegate.computeIfPresent(key, (k, old) -> {
            V replacement = remappingFunction.apply(k, old);
            if (replacement == old) return old;
            changed[0] = true;
            return this.swapping(k, old, replacement);
        });
        if (changed[0]) this.changed();
        return result;
    }

    @Override
    public V merge(K key, @NotNull V value, @NotNull BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        // 经 compute 走, 新插入的值也要过钩子
        return this.compute(key, (k, old) -> old == null ? value : remappingFunction.apply(old, value));
    }

    // 移除

    @Override
    public V remove(Object key) {
        if (!this.delegate.containsKey(key)) return null;
        V old = this.delegate.remove(key);
        this.removedThenChanged(key, old);
        return old;
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (this.removing == null) {
            boolean removed = this.delegate.remove(key, value);
            if (removed) this.changed();
            return removed;
        }
        if (!this.delegate.containsKey(key)) return false;
        V current = this.delegate.get(key);
        if (!Objects.equals(current, value)) return false;
        boolean removed = this.delegate.remove(key, current);
        if (removed) this.removedThenChanged(key, current);
        return removed;
    }

    // 移除之后过钩子再通知. 钩子抛出时变更已经落地, 通知仍要发出, 所以放在 finally 里.
    @SuppressWarnings("unchecked")
    private void removedThenChanged(Object key, V value) {
        try {
            this.removed((K) key, value);
        } finally {
            this.changed();
        }
    }

    // 一批移除之后逐个过钩子再通知一次.
    private void allRemovedThenChanged(@Nullable List<Map.Entry<K, V>> doomed) {
        try {
            BiConsumer<K, V> hook = this.removing;
            if (doomed != null && hook != null) {
                for (int i = 0; i < doomed.size(); i++) hook.accept(doomed.get(i).getKey(), doomed.get(i).getValue());
            }
        } finally {
            this.changed();
        }
    }

    // 删掉一个 key 的映射, 过钩子并通知; 没有这个 key 时什么也不做.
    private boolean removeKey(Object key) {
        if (!this.delegate.containsKey(key)) return false;
        this.removedThenChanged(key, this.delegate.remove(key));
        return true;
    }

    // 按条目谓词批量移除, 逐个 remove 以便每条都过钩子; 通知一次.
    private boolean removeEntries(Predicate<? super Map.Entry<K, V>> matching) {
        List<Map.Entry<K, V>> doomed = new ArrayList<>();
        for (Map.Entry<K, V> entry : this.delegate.entrySet()) {
            if (matching.test(entry)) doomed.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        }
        if (doomed.isEmpty()) return false;
        // 先全删完再逐个过钩子, 与 clear 同序
        List<Map.Entry<K, V>> gone = new ArrayList<>(doomed.size());
        for (int i = 0; i < doomed.size(); i++) {
            Map.Entry<K, V> entry = doomed.get(i);
            if (this.delegate.remove(entry.getKey(), entry.getValue())) gone.add(entry);
        }
        this.allRemovedThenChanged(gone);
        return true;
    }

    @Override
    public void clear() {
        if (this.delegate.isEmpty()) return;
        List<Map.Entry<K, V>> doomed = null;
        if (this.removing != null) {
            doomed = new ArrayList<>(this.delegate.size());
            for (Map.Entry<K, V> entry : this.delegate.entrySet()) doomed.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        }
        this.delegate.clear();
        this.allRemovedThenChanged(doomed);
    }

    // 视图

    @Override
    @NotNull
    public Set<K> keySet() {
        return new KeySetView();
    }

    @Override
    @NotNull
    public Collection<V> values() {
        return new ValuesView();
    }

    @Override
    @NotNull
    public Set<Map.Entry<K, V>> entrySet() {
        return new EntrySetView();
    }

    @Override
    public String toString() {
        return this.delegate.toString();
    }

    /**
     * 三个视图的共同骨架: 读直接代理, 删一律回到本对象的逐条移除, 每个视图只说自己的元素怎么对应到条目.
     *
     * @param <T> 视图元素类型
     */
    private abstract class View<T> implements Collection<T> {

        // 只读操作代理的目标
        abstract Collection<T> target();

        // 视图元素怎么从条目里取
        abstract T elementOf(Map.Entry<K, V> entry);

        // 一个视图元素对应哪些条目
        abstract boolean matches(Map.Entry<K, V> entry, Object element);

        @Override
        public int size() {
            return this.target().size();
        }

        @Override
        public boolean isEmpty() {
            return this.target().isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return this.target().contains(o);
        }

        @Override
        public boolean containsAll(@NotNull Collection<?> c) {
            return this.target().containsAll(c);
        }

        @Override
        @NotNull
        public Object[] toArray() {
            return this.snapshot().toArray();
        }

        @Override
        @NotNull
        public <A> A[] toArray(@NotNull A[] a) {
            return this.snapshot().toArray(a);
        }

        @Override
        public <A> A[] toArray(IntFunction<A[]> generator) {
            return this.snapshot().toArray(generator);
        }

        // 经本视图的迭代器复制一份, 条目视图给出的才是写穿的条目
        private List<T> snapshot() {
            List<T> copy = new ArrayList<>(this.size());
            for (T element : this) copy.add(element);
            return copy;
        }

        @Override
        public void forEach(Consumer<? super T> action) {
            for (T element : this) action.accept(element);
        }

        @Override
        public Spliterator<T> spliterator() {
            return Spliterators.spliterator(this.iterator(), this.size(), 0);
        }

        @Override
        public Stream<T> stream() {
            return StreamSupport.stream(this.spliterator(), false);
        }

        @Override
        public Stream<T> parallelStream() {
            return StreamSupport.stream(this.spliterator(), true);
        }

        @Override
        @NotNull
        public Iterator<T> iterator() {
            return new EntryIterator<>(MapSignalImpl.this.delegate.entrySet().iterator(), this::elementOf);
        }

        @Override
        public boolean add(T t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addAll(@NotNull Collection<? extends T> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            return MapSignalImpl.this.removeEntries(entry -> this.matches(entry, o));
        }

        @Override
        public boolean removeAll(@NotNull Collection<?> c) {
            return MapSignalImpl.this.removeEntries(entry -> c.contains(this.elementOf(entry)));
        }

        @Override
        public boolean retainAll(@NotNull Collection<?> c) {
            return MapSignalImpl.this.removeEntries(entry -> !c.contains(this.elementOf(entry)));
        }

        @Override
        public boolean removeIf(Predicate<? super T> filter) {
            return MapSignalImpl.this.removeEntries(entry -> filter.test(this.elementOf(entry)));
        }

        @Override
        public void clear() {
            MapSignalImpl.this.clear();
        }

        @Override
        public boolean equals(Object o) {
            return o == this || this.target().equals(o);
        }

        @Override
        public int hashCode() {
            return this.target().hashCode();
        }

        @Override
        public String toString() {
            return this.target().toString();
        }
    }

    private final class KeySetView extends View<K> implements Set<K> {

        @Override
        Collection<K> target() {
            return MapSignalImpl.this.delegate.keySet();
        }

        @Override
        K elementOf(Map.Entry<K, V> entry) {
            return entry.getKey();
        }

        @Override
        boolean matches(Map.Entry<K, V> entry, Object element) {
            return Objects.equals(entry.getKey(), element);
        }

        // 按 key 删只用删一条
        @Override
        public boolean remove(Object o) {
            return MapSignalImpl.this.removeKey(o);
        }
    }

    private final class ValuesView extends View<V> {

        @Override
        Collection<V> target() {
            return MapSignalImpl.this.delegate.values();
        }

        @Override
        V elementOf(Map.Entry<K, V> entry) {
            return entry.getValue();
        }

        @Override
        boolean matches(Map.Entry<K, V> entry, Object element) {
            return Objects.equals(entry.getValue(), element);
        }

        // Collection.remove 只删第一个等值的
        @Override
        public boolean remove(Object o) {
            for (Map.Entry<K, V> entry : MapSignalImpl.this.delegate.entrySet()) {
                if (Objects.equals(entry.getValue(), o)) {
                    return MapSignalImpl.this.removeKey(entry.getKey());
                }
            }
            return false;
        }
    }

    private final class EntrySetView extends View<Map.Entry<K, V>> implements Set<Map.Entry<K, V>> {

        @Override
        Collection<Map.Entry<K, V>> target() {
            return MapSignalImpl.this.delegate.entrySet();
        }

        @Override
        Map.Entry<K, V> elementOf(Map.Entry<K, V> entry) {
            return new EntryView(entry);
        }

        @Override
        boolean matches(Map.Entry<K, V> entry, Object element) {
            return entry.equals(element);
        }
    }

    // 把 delegate 的条目迭代器翻译成视图元素的迭代器, remove 回到本对象, 每条都过钩子.
    private final class EntryIterator<T> implements Iterator<T> {
        private final Iterator<Map.Entry<K, V>> it;
        private final Function<Map.Entry<K, V>, T> elementOf;
        private Map.Entry<K, V> last;   // 最近一次 next 给出的条目, remove 的对象

        private EntryIterator(Iterator<Map.Entry<K, V>> it, Function<Map.Entry<K, V>, T> elementOf) {
            this.it = it;
            this.elementOf = elementOf;
        }

        @Override
        public boolean hasNext() {
            return this.it.hasNext();
        }

        @Override
        public T next() {
            this.last = this.it.next();
            return this.elementOf.apply(this.last);
        }

        @Override
        public void remove() {
            Map.Entry<K, V> last = this.last;
            if (last == null) throw new IllegalStateException();
            K key = last.getKey();
            V value = last.getValue();
            this.it.remove();
            this.last = null;
            MapSignalImpl.this.removedThenChanged(key, value);
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            // 逐个经 next, 让 last 跟着走, 之后的 remove 才对得上条目
            while (this.it.hasNext()) action.accept(this.next());
        }
    }

    // 写穿的条目: setValue 先摘旧再放新, 然后通知.
    private final class EntryView implements Map.Entry<K, V> {
        private final Map.Entry<K, V> entry;

        private EntryView(Map.Entry<K, V> entry) {
            this.entry = entry;
        }

        @Override
        public K getKey() {
            return this.entry.getKey();
        }

        @Override
        public V getValue() {
            return this.entry.getValue();
        }

        @Override
        public V setValue(V value) {
            V old = this.entry.getValue();
            if (old == null ? value == null : old.equals(value)) return old;
            if (!MapSignalImpl.this.delegate.entrySet().contains(this.entry)) return old;
            this.entry.setValue(MapSignalImpl.this.swapping(this.entry.getKey(), old, value));
            MapSignalImpl.this.changed();
            return old;
        }

        @Override
        public boolean equals(Object o) {
            return this.entry.equals(o);
        }

        @Override
        public int hashCode() {
            return this.entry.hashCode();
        }

        @Override
        public String toString() {
            return this.entry.toString();
        }
    }
}
