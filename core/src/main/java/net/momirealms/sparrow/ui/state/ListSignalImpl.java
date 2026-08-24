package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * {@link ListSignal} 的实现.
 * Signal 那一半继承自 {@link CollectionSignal}, {@code List} 那一半全部转给根 {@link Facade}, 变更路径与钩子都在那边.
 */
final class ListSignalImpl<E> extends CollectionSignal<List<E>> implements ListSignal<E> {
    private final List<E> delegate;
    private final Facade root;
    @Nullable private volatile Function<E, E> adding;    // 存入之前的钩子, 挂多个时已串成一个
    @Nullable private volatile Consumer<E> removing;     // 移除之后的钩子, 同上

    ListSignalImpl(List<E> delegate) {
        this.delegate = delegate;
        this.root = new Facade(delegate);
    }

    @Override
    @NotNull
    public ListSignal<E> beforeAdd(@NotNull Function<? super E, ? extends E> hook) {
        Objects.requireNonNull(hook, "hook");
        Function<E, E> current = this.adding;
        this.adding = current == null ? hook::apply : current.andThen(hook);
        return this;
    }

    @Override
    @NotNull
    public ListSignal<E> afterRemove(@NotNull Consumer<? super E> hook) {
        Objects.requireNonNull(hook, "hook");
        Consumer<E> current = this.removing;
        this.removing = current == null ? hook::accept : current.andThen(hook);
        return this;
    }

    @Override
    public List<E> get() {
        return this;
    }

    @Override
    public int size() {
        return this.root.size();
    }

    @Override
    public boolean isEmpty() {
        return this.root.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return this.root.contains(o);
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        return this.root.containsAll(c);
    }

    @Override
    public E get(int index) {
        return this.root.get(index);
    }

    @Override
    public E getFirst() {
        return this.root.getFirst();
    }

    @Override
    public E getLast() {
        return this.root.getLast();
    }

    @Override
    public int indexOf(Object o) {
        return this.root.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return this.root.lastIndexOf(o);
    }

    @Override
    @NotNull
    public Object[] toArray() {
        return this.root.toArray();
    }

    @Override
    @NotNull
    public <T> T[] toArray(@NotNull T[] a) {
        return this.root.toArray(a);
    }

    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        return this.root.toArray(generator);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        this.root.forEach(action);
    }

    @Override
    public Spliterator<E> spliterator() {
        return this.root.spliterator();
    }

    @Override
    public Stream<E> stream() {
        return this.root.stream();
    }

    @Override
    public Stream<E> parallelStream() {
        return this.root.parallelStream();
    }

    @Override
    public boolean add(E e) {
        return this.root.add(e);
    }

    @Override
    public void add(int index, E element) {
        this.root.add(index, element);
    }

    @Override
    public void addFirst(E e) {
        this.root.addFirst(e);
    }

    @Override
    public void addLast(E e) {
        this.root.addLast(e);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends E> c) {
        return this.root.addAll(c);
    }

    @Override
    public boolean addAll(int index, @NotNull Collection<? extends E> c) {
        return this.root.addAll(index, c);
    }

    @Override
    public E set(int index, E element) {
        return this.root.set(index, element);
    }

    @Override
    public void replaceAll(@NotNull UnaryOperator<E> operator) {
        this.root.replaceAll(operator);
    }

    @Override
    public void sort(Comparator<? super E> c) {
        this.root.sort(c);
    }

    @Override
    public boolean remove(Object o) {
        return this.root.remove(o);
    }

    @Override
    public E remove(int index) {
        return this.root.remove(index);
    }

    @Override
    public E removeFirst() {
        return this.root.removeFirst();
    }

    @Override
    public E removeLast() {
        return this.root.removeLast();
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        return this.root.removeAll(c);
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        return this.root.retainAll(c);
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        return this.root.removeIf(filter);
    }

    @Override
    public void clear() {
        this.root.clear();
    }

    @Override
    @NotNull
    public List<E> subList(int fromIndex, int toIndex) {
        return this.root.subList(fromIndex, toIndex);
    }

    @Override
    public List<E> reversed() {
        return this.root.reversed();
    }

    @Override
    @NotNull
    public Iterator<E> iterator() {
        return this.root.iterator();
    }

    @Override
    @NotNull
    public ListIterator<E> listIterator() {
        return this.root.listIterator();
    }

    @Override
    @NotNull
    public ListIterator<E> listIterator(int index) {
        return this.root.listIterator(index);
    }

    @Override
    public String toString() {
        return this.delegate.toString();
    }

    /**
     * {@code List} 那一半: 每个方法代理给 target, 变更落地后推进版本与通知, 钩子也在这里跑.
     * <p>{@code subList} / {@code reversed} 给出的视图也是本类, 写穿到同一个包装器, 所以视图上的变更同样通知.
     * 视图的判等按内容(普通 {@code List} 的契约), 只有包装器本身按身份.
     */
    private final class Facade implements List<E> {
        private final List<E> target;

        private Facade(List<E> target) {
            this.target = target;
        }

        // 放入之前过钩子, 返回真正存进去的元素.
        private E adding(E element) {
            Function<E, E> hook = ListSignalImpl.this.adding;
            return hook == null ? element : hook.apply(element);
        }

        // 移除之后过钩子再通知. 钩子抛出时变更已经落地, 通知仍要发出, 所以放在 finally 里.
        private void removedThenChanged(E element) {
            try {
                Consumer<E> hook = ListSignalImpl.this.removing;
                if (hook != null) hook.accept(element);
            } finally {
                ListSignalImpl.this.changed();
            }
        }

        // 一批移除之后逐个过钩子再通知一次.
        private void allRemovedThenChanged(@Nullable List<E> doomed) {
            try {
                Consumer<E> hook = ListSignalImpl.this.removing;
                if (doomed != null && hook != null) {
                    for (int i = 0; i < doomed.size(); i++) hook.accept(doomed.get(i));
                }
            } finally {
                ListSignalImpl.this.changed();
            }
        }

        // 读取全部直接代理

        @Override
        public int size() {
            return this.target.size();
        }

        @Override
        public boolean isEmpty() {
            return this.target.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return this.target.contains(o);
        }

        @Override
        public boolean containsAll(@NotNull Collection<?> c) {
            return this.target.containsAll(c);
        }

        @Override
        public E get(int index) {
            return this.target.get(index);
        }

        @Override
        public E getFirst() {
            return this.target.getFirst();
        }

        @Override
        public E getLast() {
            return this.target.getLast();
        }

        @Override
        public int indexOf(Object o) {
            return this.target.indexOf(o);
        }

        @Override
        public int lastIndexOf(Object o) {
            return this.target.lastIndexOf(o);
        }

        @Override
        @NotNull
        public Object[] toArray() {
            return this.target.toArray();
        }

        @Override
        @NotNull
        public <T> T[] toArray(@NotNull T[] a) {
            return this.target.toArray(a);
        }

        @Override
        public <T> T[] toArray(IntFunction<T[]> generator) {
            return this.target.toArray(generator);
        }

        @Override
        public void forEach(Consumer<? super E> action) {
            this.target.forEach(action);
        }

        @Override
        public Spliterator<E> spliterator() {
            return this.target.spliterator();
        }

        @Override
        public Stream<E> stream() {
            return this.target.stream();
        }

        @Override
        public Stream<E> parallelStream() {
            return this.target.parallelStream();
        }

        // 放入

        @Override
        public boolean add(E e) {
            boolean added = this.target.add(this.adding(e));
            if (added) ListSignalImpl.this.changed();
            return added;
        }

        @Override
        public void add(int index, E element) {
            this.target.add(index, this.adding(element));
            ListSignalImpl.this.changed();
        }

        @Override
        public void addFirst(E e) {
            this.target.addFirst(this.adding(e));
            ListSignalImpl.this.changed();
        }

        @Override
        public void addLast(E e) {
            this.target.addLast(this.adding(e));
            ListSignalImpl.this.changed();
        }

        @Override
        public boolean addAll(@NotNull Collection<? extends E> c) {
            boolean added = this.target.addAll(this.allAdding(c));
            if (added) ListSignalImpl.this.changed();
            return added;
        }

        @Override
        public boolean addAll(int index, @NotNull Collection<? extends E> c) {
            boolean added = this.target.addAll(index, this.allAdding(c));
            if (added) ListSignalImpl.this.changed();
            return added;
        }

        // 批量放入先把每个元素过一遍钩子, 再一次性交给 target, 写时复制的 delegate 只复制一次.
        private Collection<? extends E> allAdding(Collection<? extends E> c) {
            Function<E, E> hook = ListSignalImpl.this.adding;
            if (hook == null || c.isEmpty()) return c;
            List<E> stored = new ArrayList<>(c.size());
            for (E element : c) stored.add(hook.apply(element));
            return stored;
        }

        // 替换

        @Override
        public E set(int index, E element) {
            Function<E, E> hook = ListSignalImpl.this.adding;
            Consumer<E> removing = ListSignalImpl.this.removing;
            if (hook == null && removing == null) {
                E old = this.target.set(index, element);
                if (old != element) ListSignalImpl.this.changed();
                return old;
            }
            // 先摘旧再放新, 旁表按位置登记时才不会把刚放进去的新条目误删
            E old = this.target.get(index);
            if (old == element) return old;
            if (removing != null) removing.accept(old);
            this.target.set(index, this.adding(element));
            ListSignalImpl.this.changed();
            return old;
        }

        @Override
        public void replaceAll(@NotNull UnaryOperator<E> operator) {
            if (this.target.isEmpty()) return;
            Function<E, E> hook = ListSignalImpl.this.adding;
            Consumer<E> removing = ListSignalImpl.this.removing;
            if (hook == null && removing == null) {
                boolean[] changed = new boolean[1];
                this.target.replaceAll(old -> {
                    E replacement = operator.apply(old);
                    changed[0] |= replacement != old;
                    return replacement;
                });
                if (changed[0]) ListSignalImpl.this.changed();
                return;
            }
            // 先把每个位置的结果与钩子算完, 再让 target 自己按位置写回, 写时复制的 delegate 只复制一次
            List<E> results = new ArrayList<>(this.target.size());
            boolean changed = false;
            for (E old : this.target) {
                E replacement = operator.apply(old);
                if (replacement != old) {
                    changed = true;
                    if (removing != null) removing.accept(old);
                    replacement = this.adding(replacement);
                }
                results.add(replacement);
            }
            if (!changed) return;
            Iterator<E> next = results.iterator();
            this.target.replaceAll(ignored -> next.next());
            ListSignalImpl.this.changed();
        }

        @Override
        public void sort(Comparator<? super E> c) {
            if (this.target.size() < 2) return;
            this.target.sort(c);
            ListSignalImpl.this.changed();
        }

        // 移除

        @Override
        public boolean remove(Object o) {
            boolean removed = this.target.remove(o);
            if (removed) {
                @SuppressWarnings("unchecked") E element = (E) o;
                this.removedThenChanged(element);
            }
            return removed;
        }

        @Override
        public E remove(int index) {
            E old = this.target.remove(index);
            this.removedThenChanged(old);
            return old;
        }

        @Override
        public E removeFirst() {
            E old = this.target.removeFirst();
            this.removedThenChanged(old);
            return old;
        }

        @Override
        public E removeLast() {
            E old = this.target.removeLast();
            this.removedThenChanged(old);
            return old;
        }

        @Override
        public boolean removeAll(@NotNull Collection<?> c) {
            // 没挂钩子时谓词不会被用到, 别白建一份查找集
            Collection<?> lookup = ListSignalImpl.this.removing == null ? c : lookupOf(c);
            return this.removeMatching(lookup::contains, () -> this.target.removeAll(c));
        }

        @Override
        public boolean retainAll(@NotNull Collection<?> c) {
            Collection<?> lookup = ListSignalImpl.this.removing == null ? c : lookupOf(c);
            return this.removeMatching(element -> !lookup.contains(element), () -> this.target.retainAll(c));
        }

        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            return this.removeMatching(filter, () -> this.target.removeIf(filter));
        }

        // 批量移除: 有钩子时先收集命中的元素, 再交给 target 的批量方法, 最后逐个回调. 写时复制的迭代器不支持逐个删, 所以不能改写成迭代删.
        private boolean removeMatching(Predicate<? super E> matching, BooleanSupplier bulkRemove) {
            List<E> doomed = null;
            if (ListSignalImpl.this.removing != null) {
                doomed = new ArrayList<>();
                for (E element : this.target) {
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
            if (this.target.isEmpty()) return;
            List<E> doomed = ListSignalImpl.this.removing == null ? null : new ArrayList<>(this.target);
            this.target.clear();
            this.allRemovedThenChanged(doomed);
        }

        // 视图与迭代器, 都写穿到同一个包装器

        @Override
        @NotNull
        public List<E> subList(int fromIndex, int toIndex) {
            return new Facade(this.target.subList(fromIndex, toIndex));
        }

        @Override
        public List<E> reversed() {
            return new Facade(this.target.reversed());
        }

        @Override
        @NotNull
        public Iterator<E> iterator() {
            return new MutatingIterator(this.target.listIterator());
        }

        @Override
        @NotNull
        public ListIterator<E> listIterator() {
            return new MutatingIterator(this.target.listIterator());
        }

        @Override
        @NotNull
        public ListIterator<E> listIterator(int index) {
            return new MutatingIterator(this.target.listIterator(index));
        }

        @Override
        public boolean equals(Object o) {
            return o == this || this.target.equals(o);
        }

        @Override
        public int hashCode() {
            return this.target.hashCode();
        }

        @Override
        public String toString() {
            return this.target.toString();
        }

        // 迭代器上的 remove / set / add 同样经钩子与通知, 每个动作各通知一次.
        private final class MutatingIterator implements ListIterator<E> {
            private final ListIterator<E> it;
            private E last;   // 最近一次 next / previous 给出的元素, set 与 remove 的对象
            private boolean canModify;

            private MutatingIterator(ListIterator<E> it) {
                this.it = it;
            }

            @Override
            public boolean hasNext() {
                return this.it.hasNext();
            }

            @Override
            public E next() {
                E next = this.it.next();
                this.last = next;
                this.canModify = true;
                return next;
            }

            @Override
            public boolean hasPrevious() {
                return this.it.hasPrevious();
            }

            @Override
            public E previous() {
                E previous = this.it.previous();
                this.last = previous;
                this.canModify = true;
                return previous;
            }

            @Override
            public int nextIndex() {
                return this.it.nextIndex();
            }

            @Override
            public int previousIndex() {
                return this.it.previousIndex();
            }

            @Override
            public void remove() {
                this.it.remove();
                this.canModify = false;
                Facade.this.removedThenChanged(this.last);
            }

            @Override
            public void set(E e) {
                if (!this.canModify) throw new IllegalStateException();
                if (this.last == e) return;
                Consumer<E> removing = ListSignalImpl.this.removing;
                if (removing != null) removing.accept(this.last);
                E stored = Facade.this.adding(e);
                this.it.set(stored);
                this.last = stored;
                ListSignalImpl.this.changed();
            }

            @Override
            public void add(E e) {
                this.it.add(Facade.this.adding(e));
                this.canModify = false;
                ListSignalImpl.this.changed();
            }

            @Override
            public void forEachRemaining(Consumer<? super E> action) {
                // 逐个经 next, 让 last 跟着走, 之后的 remove 才对得上元素
                while (this.it.hasNext()) action.accept(this.next());
            }
        }
    }
}
