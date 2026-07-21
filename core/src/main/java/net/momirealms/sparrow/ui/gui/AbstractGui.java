package net.momirealms.sparrow.ui.gui;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 保存 GUI 的槽位元素, 背景, 冻结状态和逐槽订阅.
 *
 * <p>状态修改和订阅链更改使用同一把短锁, 观察者回调始终在锁外执行.</p>
 */
abstract non-sealed class AbstractGui implements Gui {
    private final Structure structure;
    private final SlotElement[] elements;
    private final SlotObserver[] observers; // 每个槽位对应一条订阅链的头节点

    private ItemProvider background;
    private boolean frozen;

    AbstractGui(Structure structure, SlotElement[] elements, ItemProvider background, boolean frozen) {
        this.structure = structure;
        this.elements = elements;
        this.background = background;
        this.frozen = frozen;
        this.observers = new SlotObserver[elements.length];
    }

    @Override
    public final @NotNull GuiSize size() {
        return this.structure.size();
    }

    @Override
    public final @NotNull Structure structure() {
        return this.structure;
    }

    @Override
    public final synchronized @NotNull SlotElement element(int slot) {
        return this.elements[slot];
    }

    @Override
    public final synchronized SlotElement @NotNull [] elements() {
        return this.elements.clone();
    }

    @Override
    public final void setElement(int slot, @NotNull SlotElement element) {
        Objects.requireNonNull(element, "element");
        SlotObserver[] observers;
        synchronized (this) {
            SlotElement previous = this.elements[slot];
            if (previous == element) {
                return;
            }
            this.elements[slot] = element;
            observers = this.snapshot(this.observers[slot]);
        }
        this.publish(observers);
    }

    @Override
    public final void setElements(
            @NotNull SlotSequence slots,
            @NotNull SlotElementSupplier supplier,
            boolean replaceExisting
    ) {
        if (!this.size().equals(slots.guiSize())) {
            throw new IllegalArgumentException("slot sequence belongs to " + slots.guiSize() + ", expected " + this.size());
        }

        // 先在锁外生成全部元素, 任何失败都不会留下半批修改
        int length = slots.length();
        SlotElement[] replacements = new SlotElement[length];
        for (int occurrence = 0; occurrence < length; occurrence++) {
            SlotElement replacement = supplier.get(slots, occurrence);
            replacements[occurrence] = replacement;
        }

        SlotObserver[][] changedObservers = new SlotObserver[length][];
        int[] indices = slots.trustedArray();
        // 在同一次短锁中写入元素, 并保存需要通知的订阅快照
        synchronized (this) {
            for (int occurrence = 0; occurrence < length; occurrence++) {
                int slot = indices[occurrence];
                SlotElement previous = this.elements[slot];
                if (!replaceExisting && previous != SlotElement.Empty.INSTANCE) {
                    continue;
                }

                SlotElement replacement = replacements[occurrence];
                if (previous != replacement) {
                    this.elements[slot] = replacement;
                    changedObservers[occurrence] = this.snapshot(this.observers[slot]);
                }
            }
        }
        // 回调可能执行用户代码, 因此必须在 GUI 锁外发布
        this.publish(changedObservers);
    }

    @Override
    public final void addElements(SlotElement @NotNull ... newElements) {
        for (SlotElement element : newElements) {
            if (element == null) {
                throw new NullPointerException("elements must not contain null");
            }
        }

        this.addElementsTrusted(newElements);
    }

    @Override
    public final void addItems(Item @NotNull ... items) {
        SlotElement[] elements = new SlotElement[items.length];
        for (int index = 0; index < items.length; index++) {
            elements[index] = new SlotElement.Item(items[index]);
        }
        this.addElementsTrusted(elements);
    }

    /**
     * 把已验证元素依次放入最靠前的空槽位.
     */
    private void addElementsTrusted(SlotElement[] newElements) {
        SlotObserver[][] changedObservers = new SlotObserver[Math.min(newElements.length, this.elements.length)][];
        int changed = 0;
        synchronized (this) {
            int searchFrom = 0;
            for (SlotElement element : newElements) {
                if (element == SlotElement.Empty.INSTANCE) {
                    continue;
                }
                while (searchFrom < this.elements.length
                        && this.elements[searchFrom] != SlotElement.Empty.INSTANCE) {
                    searchFrom++;
                }
                if (searchFrom == this.elements.length) {
                    break;
                }
                this.elements[searchFrom] = element;
                changedObservers[changed++] = this.snapshot(this.observers[searchFrom]);
                searchFrom++;
            }
        }
        this.publish(changedObservers, changed);
    }

    @Override
    public final void dirty(@NotNull SlotSequence slots) {
        if (!this.size().equals(slots.guiSize())) {
            throw new IllegalArgumentException("slot sequence belongs to " + slots.guiSize() + ", expected " + this.size());
        }

        SlotObserver[][] observers = new SlotObserver[slots.length()][];
        int[] indices = slots.trustedArray();
        synchronized (this) {
            for (int occurrence = 0; occurrence < indices.length; occurrence++) {
                observers[occurrence] = this.snapshot(this.observers[indices[occurrence]]);
            }
        }
        this.publish(observers);
    }

    @Override
    public final synchronized @Nullable ItemProvider background() {
        return this.background;
    }

    @Override
    public final void setBackground(@Nullable ItemProvider background) {
        SlotObserver[][] observers;
        synchronized (this) {
            if (this.background == background) {
                return;
            }
            this.background = background;
            observers = this.snapshotAll();
        }
        this.publish(observers);
    }

    @Override
    public final synchronized boolean frozen() {
        return this.frozen;
    }

    @Override
    public final void setFrozen(boolean frozen) {
        SlotObserver[][] observers;
        synchronized (this) {
            if (this.frozen == frozen) {
                return;
            }
            this.frozen = frozen;
            observers = this.snapshotAll();
        }
        this.publish(observers);
    }

    @Override
    public final synchronized @NotNull GuiSlotAttachment attach(int slot, @NotNull Observer<? super Gui> observer) {
        Objects.requireNonNull(observer, "observer");
        SlotObserver head = this.observers[slot];
        SlotObserver subscription = new SlotObserver(this, slot, observer, head);
        if (head != null) {
            head.previous = subscription;
        }
        this.observers[slot] = subscription;
        return new GuiSlotAttachment(
                this.elements[slot],
                this.background,
                this.frozen,
                subscription
        );
    }

    /**
     * 从槽位订阅链中断开指定节点, 并清除它持有的引用.
     */
    private synchronized void remove(SlotObserver subscription) {
        if (!subscription.active) {
            return;
        }
        subscription.active = false;

        SlotObserver previous = subscription.previous;
        SlotObserver next = subscription.next;
        if (previous == null) {
            this.observers[subscription.slot] = next;
        } else {
            previous.next = next;
        }
        if (next != null) {
            next.previous = previous;
        }
        subscription.previous = null;
        subscription.next = null;
        subscription.observer = null;
        subscription.owner = null;
    }

    /**
     * 复制一个槽位中仍然有效的订阅, 供锁外回调使用.
     */
    private SlotObserver[] snapshot(SlotObserver head) {
        if (head == null) {
            return null;
        }

        int size = 0;
        for (SlotObserver current = head; current != null; current = current.next) {
            if (current.active) {
                size++;
            }
        }
        if (size == 0) {
            return null;
        }

        SlotObserver[] snapshot = new SlotObserver[size];
        int index = 0;
        for (SlotObserver current = head; current != null; current = current.next) {
            if (current.active) {
                snapshot[index++] = current;
            }
        }
        return snapshot;
    }

    /**
     * 复制所有槽位的订阅, 用于背景或冻结状态更改.
     */
    private SlotObserver[][] snapshotAll() {
        SlotObserver[][] snapshots = new SlotObserver[this.observers.length][];
        for (int slot = 0; slot < snapshots.length; slot++) {
            snapshots[slot] = this.snapshot(this.observers[slot]);
        }
        return snapshots;
    }

    private void publish(SlotObserver[] observers) {
        RuntimeException failure = this.notify(observers, null);
        if (failure != null) {
            throw failure;
        }
    }

    private void publish(SlotObserver[][] observers) {
        this.publish(observers, observers.length);
    }

    private void publish(SlotObserver[][] observers, int length) {
        RuntimeException failure = null;
        for (int index = 0; index < length; index++) {
            failure = this.notify(observers[index], failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 通知快照中仍然有效的观察者, 并合并回调抛出的异常.
     */
    private RuntimeException notify(SlotObserver[] observers, RuntimeException failure) {
        if (observers == null) {
            return failure;
        }
        for (SlotObserver current : observers) {
            Observer<? super Gui> observer = current.observer;
            if (observer == null) {
                continue;
            }
            try {
                observer.onUpdate(this);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        return failure;
    }

    /**
     * 一次槽位订阅在双向链中的节点, 调用 close 可以直接断开该节点.
     */
    private static final class SlotObserver implements Subscription {
        private volatile AbstractGui owner;
        private final int slot;
        private volatile Observer<? super Gui> observer;

        private volatile SlotObserver next; // 更早加入的订阅
        private volatile boolean active = true;
        private SlotObserver previous; // 更晚加入的订阅

        private SlotObserver(
                AbstractGui owner,
                int slot,
                Observer<? super Gui> observer,
                SlotObserver next
        ) {
            this.owner = owner;
            this.slot = slot;
            this.observer = observer;
            this.next = next;
        }

        @Override
        public boolean isClosed() {
            return !this.active;
        }

        @Override
        public void close() {
            AbstractGui owner = this.owner;
            if (owner != null) {
                owner.remove(this);
            }
        }
    }
}
