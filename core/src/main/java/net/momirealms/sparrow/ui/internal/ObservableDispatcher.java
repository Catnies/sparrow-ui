package net.momirealms.sparrow.ui.internal;

import net.momirealms.sparrow.ui.Observable;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.util.ExceptionCollector;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class ObservableDispatcher<T> implements Observable<T> {

    private final CopyOnWriteArrayList<Entry<T>> entries = new CopyOnWriteArrayList<>();

    @Override
    public Subscription subscribe(Observer<? super T> observer) {
        Objects.requireNonNull(observer, "observer");
        Entry<T> entry = new Entry<>(this, observer);
        entries.add(entry);
        return entry;
    }

    /**
     * 向遍历时仍处于活动状态的每个订阅发布更新.
     *
     * <p>运行时异常会被隔离, 直到所有活动观察者均已遍历完成. 随后重新抛出第一个异常,
     * 并将之后的异常作为被抑制异常附加到该异常中.</p>
     *
     * @param update 更新数据
     */
    public void publish(T update) {
        ExceptionCollector<RuntimeException> collector = new ExceptionCollector<>(RuntimeException.class);

        for (Entry<T> entry : entries) {
            Observer<? super T> observer = entry.observer();
            if (observer == null) {
                continue;
            }

            try {
                observer.onUpdate(update);
            } catch (RuntimeException exception) {
                collector.add(exception);
            }
        }

        collector.throwIfPresent();
    }

    public int subscriptionCount() {
        return entries.size();
    }

    private void remove(Entry<T> entry) {
        entries.remove(entry);
    }

    private static final class Entry<T> implements Subscription {
        private final ObservableDispatcher<T> owner;
        private final AtomicReference<Observer<? super T>> observer;

        private Entry(ObservableDispatcher<T> owner, Observer<? super T> observer) {
            this.owner = owner;
            this.observer = new AtomicReference<>(observer);
        }

        private Observer<? super T> observer() {
            return observer.get();
        }

        @Override
        public boolean isClosed() {
            return observer.get() == null;
        }

        @Override
        public void close() {
            if (observer.getAndSet(null) != null) {
                owner.remove(this);
            }
        }
    }
}
