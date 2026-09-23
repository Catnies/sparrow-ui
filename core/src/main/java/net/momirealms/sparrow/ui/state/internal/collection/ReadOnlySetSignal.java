package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.SetSignal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class ReadOnlySetSignal<E> extends AbstractSignal<Set<E>> implements SetSignal<E> {
    private final SetSignalImpl<E> source;
    private final Set<E> view;
    private Subscription upstream;

    ReadOnlySetSignal(SetSignalImpl<E> source) {
        this.source = source;
        this.view = Collections.unmodifiableSet(source);
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
    public Set<E> get() {
        return this;
    }

    @Override
    @NotNull
    public SetSignal<E> asReadOnly() {
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
    public boolean contains(Object o) {
        return this.view.contains(o);
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        return this.view.containsAll(c);
    }

    @Override
    public Iterator<E> iterator() {
        return this.view.iterator();
    }

    @Override
    public Object[] toArray() {
        return this.view.toArray();
    }

    @Override
    public <T> T[] toArray(@NotNull T[] a) {
        return this.view.toArray(a);
    }

    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        return this.view.toArray(generator);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        this.view.forEach(action);
    }

    @Override
    public Spliterator<E> spliterator() {
        return this.view.spliterator();
    }

    @Override
    public Stream<E> stream() {
        return this.view.stream();
    }

    @Override
    public Stream<E> parallelStream() {
        return this.view.parallelStream();
    }

    @Override
    public boolean add(E element) {
        return this.view.add(element);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends E> c) {
        return this.view.addAll(c);
    }

    @Override
    public boolean remove(Object o) {
        return this.view.remove(o);
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        return this.view.removeAll(c);
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        return this.view.retainAll(c);
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        return this.view.removeIf(filter);
    }

    @Override
    public void clear() {
        this.view.clear();
    }

    @Override
    public String toString() {
        return this.view.toString();
    }
}
