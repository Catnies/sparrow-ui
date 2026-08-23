package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class SetSignalImpl<E> extends CollectionSignal<Set<E>> implements SetSignal<E> {
    private final Set<E> delegate;
    @Nullable private volatile Function<E, E> adding;    // 存入之前的钩子, 挂多个时已串成一个
    @Nullable private volatile Consumer<E> removing;     // 移除之后的钩子, 同上

    SetSignalImpl(Set<E> delegate) {
        this.delegate = delegate;
    }

    @Override
    @NotNull
    public SetSignal<E> onAdd(@NotNull Function<? super E, ? extends E> hook) {
        Objects.requireNonNull(hook, "hook");
        Function<E, E> current = this.adding;
        this.adding = current == null ? hook::apply : current.andThen(hook);
        return this;
    }

    @Override
    @NotNull
    public SetSignal<E> onRemoved(@NotNull Consumer<? super E> hook) {
        Objects.requireNonNull(hook, "hook");
        Consumer<E> current = this.removing;
        this.removing = current == null ? hook::accept : current.andThen(hook);
        return this;
    }

    @Override
    public Set<E> get() {
        return this;
    }

    // 移除之后过钩子再通知. 钩子抛出时变更已经落地, 通知仍要发出, 所以放在 finally 里.
    private void removedThenChanged(E element) {
        try {
            Consumer<E> hook = this.removing;
            if (hook != null) hook.accept(element);
        } finally {
            this.changed();
        }
    }

    // 一批移除之后逐个过钩子再通知一次.
    private void allRemovedThenChanged(@Nullable List<E> doomed) {
        try {
            Consumer<E> hook = this.removing;
            if (doomed != null && hook != null) {
                for (int i = 0; i < doomed.size(); i++) hook.accept(doomed.get(i));
            }
        } finally {
            this.changed();
        }
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
    public boolean contains(Object o) {
        return this.delegate.contains(o);
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        return this.delegate.containsAll(c);
    }

    @Override
    @NotNull
    public Object[] toArray() {
        return this.delegate.toArray();
    }

    @Override
    @NotNull
    public <T> T[] toArray(@NotNull T[] a) {
        return this.delegate.toArray(a);
    }

    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        return this.delegate.toArray(generator);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        this.delegate.forEach(action);
    }

    @Override
    public Spliterator<E> spliterator() {
        return this.delegate.spliterator();
    }

    @Override
    public Stream<E> stream() {
        return this.delegate.stream();
    }

    @Override
    public Stream<E> parallelStream() {
        return this.delegate.parallelStream();
    }

    // 放入

    @Override
    public boolean add(E e) {
        Function<E, E> hook = this.adding;
        if (hook == null) {
            boolean added = this.delegate.add(e);
            if (added) this.changed();
            return added;
        }
        // 先用原元素查重, 已有就不惊动钩子
        if (this.delegate.contains(e)) return false;
        boolean added = this.delegate.add(hook.apply(e));
        if (added) this.changed();
        return added;
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends E> c) {
        if (c.isEmpty()) return false;
        Collection<? extends E> stored = c;
        Function<E, E> hook = this.adding;
        if (hook != null) {
            // 先把新元素过一遍钩子, 再一次性交给 delegate, 写时复制的只复制一次
            List<E> fresh = new ArrayList<>();
            for (E element : c) {
                if (!this.delegate.contains(element)) fresh.add(hook.apply(element));
            }
            stored = fresh;
        }
        boolean added = this.delegate.addAll(stored);
        if (added) this.changed();
        return added;
    }

    // 移除

    @Override
    public boolean remove(Object o) {
        boolean removed = this.delegate.remove(o);
        if (removed) {
            @SuppressWarnings("unchecked") E element = (E) o;
            this.removedThenChanged(element);
        }
        return removed;
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        return this.removeMatching(c::contains, () -> this.delegate.removeAll(c));
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        return this.removeMatching(element -> !c.contains(element), () -> this.delegate.retainAll(c));
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        return this.removeMatching(filter, () -> this.delegate.removeIf(filter));
    }

    // 批量移除: 有钩子时先收集命中的元素, 再交给 delegate 的批量方法, 最后逐个回调. 写时复制的迭代器不支持逐个删.
    private boolean removeMatching(Predicate<? super E> matching, BooleanSupplier bulkRemove) {
        List<E> doomed = null;
        if (this.removing != null) {
            doomed = new ArrayList<>();
            for (E element : this.delegate) {
                if (matching.test(element)) doomed.add(element);
            }
        }
        boolean removed = bulkRemove.getAsBoolean();
        if (!removed) return false;
        this.allRemovedThenChanged(doomed);
        return true;
    }

    @Override
    public void clear() {
        if (this.delegate.isEmpty()) return;
        List<E> doomed = this.removing == null ? null : new ArrayList<>(this.delegate);
        this.delegate.clear();
        this.allRemovedThenChanged(doomed);
    }

    @Override
    @NotNull
    public Iterator<E> iterator() {
        return new MutatingIterator(this.delegate.iterator());
    }

    @Override
    public String toString() {
        return this.delegate.toString();
    }

    // 迭代器上的 remove 同样经钩子与通知.
    private final class MutatingIterator implements Iterator<E> {
        private final Iterator<E> it;
        private E last;   // 最近一次 next 给出的元素, remove 的对象

        private MutatingIterator(Iterator<E> it) {
            this.it = it;
        }

        @Override
        public boolean hasNext() {
            return this.it.hasNext();
        }

        @Override
        public E next() {
            return this.last = this.it.next();
        }

        @Override
        public void remove() {
            this.it.remove();
            SetSignalImpl.this.removedThenChanged(this.last);
        }

        @Override
        public void forEachRemaining(Consumer<? super E> action) {
            // 逐个经 next, 让 last 跟着走, 之后的 remove 才对得上元素
            while (this.it.hasNext()) action.accept(this.next());
        }
    }
}
