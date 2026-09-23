package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.MapSignal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

final class ReadOnlyMapSignal<K, V> extends AbstractSignal<Map<K, V>> implements MapSignal<K, V> {
    private final MapSignalImpl<K, V> source;
    private final Map<K, V> view;
    @Nullable private Set<Map.Entry<K, V>> entries;
    private Subscription upstream;

    ReadOnlyMapSignal(MapSignalImpl<K, V> source) {
        this.source = source;
        this.view = Collections.unmodifiableMap(source);
    }

    @Override
    protected void onActive() {
        this.upstream = this.linkTo(this.source, this::notifyDirty);
    }

    @Override
    protected void onInactive() {
        this.upstream.close();
        this.upstream = null;
    }

    @Override
    public long version() {
        return this.source.version();
    }

    @Override
    public Map<K, V> get() {
        return this;
    }

    @Override
    @NotNull
    public MapSignal<K, V> asReadOnly() {
        return this;
    }

    @Override
    public int size() {
        return this.view.size();
    }

    @Override
    public boolean isEmpty() {
        return this.view.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return this.view.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return this.view.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return this.view.get(key);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return this.view.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        this.view.forEach(action);
    }

    @Override
    public V put(K key, V value) {
        return this.view.put(key, value);
    }

    @Override
    public void putAll(@NotNull Map<? extends K, ? extends V> values) {
        this.view.putAll(values);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return this.view.putIfAbsent(key, value);
    }

    @Override
    public V replace(K key, V value) {
        return this.view.replace(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return this.view.replace(key, oldValue, newValue);
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        this.view.replaceAll(function);
    }

    @Override
    public V compute(K key, @NotNull BiFunction<? super K, ? super V, ? extends V> function) {
        return this.view.compute(key, function);
    }

    @Override
    public V computeIfAbsent(K key, @NotNull Function<? super K, ? extends V> function) {
        return this.view.computeIfAbsent(key, function);
    }

    @Override
    public V computeIfPresent(K key, @NotNull BiFunction<? super K, ? super V, ? extends V> function) {
        return this.view.computeIfPresent(key, function);
    }

    @Override
    public V merge(K key, @NotNull V value, @NotNull BiFunction<? super V, ? super V, ? extends V> function) {
        return this.view.merge(key, value, function);
    }

    @Override
    public V remove(Object key) {
        return this.view.remove(key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return this.view.remove(key, value);
    }

    @Override
    public void clear() {
        this.view.clear();
    }

    @Override
    public Set<K> keySet() {
        return this.view.keySet();
    }

    @Override
    public Collection<V> values() {
        return this.view.values();
    }

    @Override
    public synchronized Set<Map.Entry<K, V>> entrySet() {
        if (this.entries == null) {
            this.entries = Collections.unmodifiableSet(new EntrySet(this.view.entrySet()));
        }
        return this.entries;
    }

    @Override
    public String toString() {
        return this.view.toString();
    }

    private final class EntrySet extends AbstractSet<Map.Entry<K, V>> {
        private final Set<Map.Entry<K, V>> entries;

        private EntrySet(Set<Map.Entry<K, V>> entries) {
            this.entries = entries;
        }

        @Override
        public int size() {
            return this.entries.size();
        }

        @Override
        public boolean contains(Object entry) {
            return this.entries.contains(entry);
        }

        @Override
        @NotNull
        public Iterator<Map.Entry<K, V>> iterator() {
            return this.entries.iterator();
        }

        @Override
        public Spliterator<Map.Entry<K, V>> spliterator() {
            return this.entries.spliterator();
        }

        @Override
        @NotNull
        public Object[] toArray() {
            return this.entries.toArray();
        }

        @Override
        @NotNull
        public <T> T[] toArray(@NotNull T[] array) {
            return this.entries.toArray(array);
        }

        @Override
        public <T> T[] toArray(IntFunction<T[]> generator) {
            // 生成数组也经过包装 Entry 的入口, JDK 门面的同名重载会直接转发到底层集合.
            return this.entries.toArray(generator.apply(0));
        }
    }
}
