package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

final class ReadOnlyListSignal<E> extends AbstractSignal<List<E>> implements ListSignal<E> {
    private final ListSignalImpl<E> source;
    private final List<E> view;
    private Subscription upstream;

    ReadOnlyListSignal(ListSignalImpl<E> source) {
        this.source = source;
        this.view = Collections.unmodifiableList(source);
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
    public List<E> get() {
        return this;
    }

    @Override
    @NotNull
    public ListSignal<E> asReadOnly() {
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
    public E get(int index) {
        return this.view.get(index);
    }

    @Override
    public E getFirst() {
        return this.view.getFirst();
    }

    @Override
    public E getLast() {
        return this.view.getLast();
    }

    @Override
    public int indexOf(Object o) {
        return this.view.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return this.view.lastIndexOf(o);
    }

    @Override
    @NotNull
    public Object[] toArray() {
        return this.view.toArray();
    }

    @Override
    @NotNull
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
    public boolean add(E e) {
        return this.view.add(e);
    }

    @Override
    public void add(int index, E element) {
        this.view.add(index, element);
    }

    @Override
    public void addFirst(E e) {
        this.view.addFirst(e);
    }

    @Override
    public void addLast(E e) {
        this.view.addLast(e);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends E> c) {
        return this.view.addAll(c);
    }

    @Override
    public boolean addAll(int index, @NotNull Collection<? extends E> c) {
        return this.view.addAll(index, c);
    }

    @Override
    public E set(int index, E element) {
        return this.view.set(index, element);
    }

    @Override
    public void replaceAll(@NotNull UnaryOperator<E> operator) {
        this.view.replaceAll(operator);
    }

    @Override
    public void sort(Comparator<? super E> c) {
        this.view.sort(c);
    }

    @Override
    public boolean remove(Object o) {
        return this.view.remove(o);
    }

    @Override
    public E remove(int index) {
        return this.view.remove(index);
    }

    @Override
    public E removeFirst() {
        return this.view.removeFirst();
    }

    @Override
    public E removeLast() {
        return this.view.removeLast();
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
    @NotNull
    public List<E> subList(int fromIndex, int toIndex) {
        return this.view.subList(fromIndex, toIndex);
    }

    @Override
    public List<E> reversed() {
        return this.view.reversed();
    }

    @Override
    @NotNull
    public Iterator<E> iterator() {
        return this.view.iterator();
    }

    @Override
    @NotNull
    public ListIterator<E> listIterator() {
        return this.view.listIterator();
    }

    @Override
    @NotNull
    public ListIterator<E> listIterator(int index) {
        return this.view.listIterator(index);
    }

    @Override
    public String toString() {
        return this.view.toString();
    }

}
