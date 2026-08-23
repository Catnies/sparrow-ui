package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.InventorySequence;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import net.momirealms.sparrow.ui.visual.PaneVisual;
import net.momirealms.sparrow.ui.visual.PaneVisualImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

abstract non-sealed class AbstractPane implements Pane {
    private final Structure structure;      // 槽位布局
    private final Element[] elements;   // 每个槽位当前保存的元素
    private final SlotObserver[] observers; // 每个槽位对应一条订阅链的头节点
    private final Bindings bindings = new Bindings(); // 持有的 Signal 绑定
    private final PaneVisualImpl visual;    // 视觉配置, 空槽背景与逐槽显示路径失效订阅

    private boolean frozen;             // 是否禁止玩家交互
    @Nullable private volatile InventorySequence ownSequence;                  // 额外参与的 Inventory 序列, 写时整体替换为新的不可变快照, 读不加锁
    private volatile Set<InventorySequence> declaredSequences = Set.of();      // 整条声明进来的, 可以摘掉
    private volatile Set<InventorySequence> participatingSequences = Set.of(); // 上面两者的并集, 按声明顺序, 只在声明变化时重建

    AbstractPane(Structure structure, Element[] elements, ItemProvider background, boolean frozen) {
        this.structure = structure;
        this.elements = elements;
        this.visual = new PaneVisualImpl(this.bindings, elements.length);
        this.frozen = frozen;
        this.observers = new SlotObserver[elements.length];
        if (background != null) {
            this.visual.background(background);
        }
    }

    @Override
    @NotNull
    public final PaneSize size() {
        return this.structure.size();
    }

    @Override
    @NotNull
    public final Structure structure() {
        return this.structure;
    }

    @Override
    @NotNull
    public final synchronized Element element(int slot) {
        return this.elements[slot];
    }

    @Override
    public final synchronized Element @NotNull [] elements() {
        return this.elements.clone();
    }

    @Override
    public final void setElement(int slot, @NotNull Element element) {
        Objects.requireNonNull(element, "element");
        SlotObserver[] observers;
        synchronized (this) {
            // 同一 Element 实例不会触发通知
            Element previous = this.elements[slot];
            if (previous == element) {
                return;
            }
            this.elements[slot] = element;
            observers = this.snapshot(this.observers[slot]);
        }
        // 用户回调在 Pane 锁外执行
        this.publish(observers);
    }

    // 全部元素生成成功后再进入短锁应用
    @Override
    public final void setElements(
            @NotNull SlotSequence slots,
            @NotNull ElementSupplier supplier,
            boolean replaceExisting
    ) {
        if (!this.size().equals(slots.paneSize())) {
            throw new IllegalArgumentException("slot sequence belongs to " + slots.paneSize() + ", expected " + this.size());
        }

        // 先在锁外生成全部元素.
        int length = slots.length();
        Element[] replacements = new Element[length];
        for (int occurrence = 0; occurrence < length; occurrence++) {
            Element replacement = supplier.get(slots, occurrence);
            replacements[occurrence] = replacement;
        }

        SlotObserver[][] changedObservers = new SlotObserver[length][];
        int[] indices = slots.unsafeSlots();
        // 在同一次短锁中写入元素, 并保存需要通知的订阅快照.
        synchronized (this) {
            for (int occurrence = 0; occurrence < length; occurrence++) {
                int slot = indices[occurrence];
                Element previous = this.elements[slot];
                if (!replaceExisting && previous != Element.Empty.INSTANCE) {
                    continue;
                }

                Element replacement = replacements[occurrence];
                if (previous != replacement) {
                    this.elements[slot] = replacement;
                    changedObservers[occurrence] = this.snapshot(this.observers[slot]);
                }
            }
        }
        this.publish(changedObservers);
    }

    @Override
    public final void addElements(Element @NotNull ... newElements) {
        // 先校验整批输入, 失败时 Pane 保持不变
        for (Element element : newElements) {
            if (element == null) {
                throw new NullPointerException("elements must not contain null");
            }
        }

        this.addElementsTrusted(newElements);
    }

    @Override
    public final void addItems(Item @NotNull ... items) {
        // 先把 Item 包装成槽位元素, 再复用同一入口
        Element[] elements = new Element[items.length];
        for (int index = 0; index < items.length; index++) {
            elements[index] = new Element.Item(items[index]);
        }
        this.addElementsTrusted(elements);
    }

    // 把已验证元素依次放入最靠前的空槽位.
    private void addElementsTrusted(Element[] newElements) {
        SlotObserver[][] changedObservers = new SlotObserver[Math.min(newElements.length, this.elements.length)][];
        int changed = 0;
        synchronized (this) {
            int searchFrom = 0;
            for (Element element : newElements) {
                if (element == Element.Empty.INSTANCE) {
                    continue;
                }
                // 从上次找到的位置继续向右找下一个空槽位
                while (searchFrom < this.elements.length
                        && this.elements[searchFrom] != Element.Empty.INSTANCE) {
                    searchFrom++;
                }
                // Pane 已满, 剩余元素放不下时提前结束
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
        if (!this.size().equals(slots.paneSize())) {
            throw new IllegalArgumentException("slot sequence belongs to " + slots.paneSize() + ", expected " + this.size());
        }

        SlotObserver[][] observers = new SlotObserver[slots.length()][];
        int[] indices = slots.unsafeSlots();
        // 元素本身没变, 只需取出订阅快照重新通知
        synchronized (this) {
            for (int occurrence = 0; occurrence < indices.length; occurrence++) {
                observers[occurrence] = this.snapshot(this.observers[indices[occurrence]]);
            }
        }
        this.publish(observers);
    }

    @Override
    @NotNull
    public final PaneVisual visual() {
        return this.visual;
    }

    @Override
    @Nullable
    public final ItemProvider background() {
        return this.visual.background();
    }

    @Override
    public final void setBackground(@Nullable ItemProvider background) {
        this.visual.background(background);
    }

    @Override
    public final synchronized boolean frozen() {
        return this.frozen;
    }

    @Override
    public final void setFrozen(boolean frozen) {
        SlotObserver[][] observers;
        synchronized (this) {
            // 冻结状态未变化时不触发任何通知
            if (this.frozen == frozen) {
                return;
            }
            this.frozen = frozen;
            // 冻结状态影响所有槽位的交互, 需要通知全部槽位的订阅
            observers = this.snapshotAll();
        }
        this.publish(observers);
    }

    @Override
    public final void linkInventory(@NotNull SparrowInventory inventory) {
        Objects.requireNonNull(inventory);
        this.ownSequence().add(inventory);
    }

    @Override
    public final boolean unlinkInventory(@NotNull SparrowInventory inventory) {
        Objects.requireNonNull(inventory);
        InventorySequence own = this.ownSequence;
        return own != null && own.remove(inventory);
    }

    @Override
    @NotNull
    public final List<SparrowInventory> linkedInventories() {
        InventorySequence own = this.ownSequence;
        return own == null ? List.of() : own.inventories();
    }

    @Override
    public final void linkInventory(@NotNull InventorySequence sequence) {
        Objects.requireNonNull(sequence);
        synchronized (this) {
            // 逐个声明的内部序列由 linkedInventories 一组方法管理
            if (sequence == this.ownSequence || this.declaredSequences.contains(sequence)) {
                return;
            }
            this.declaredSequences = withAdded(this.declaredSequences, sequence);
            this.participatingSequences = withAdded(this.participatingSequences, sequence);
        }
    }

    @Override
    public final boolean unlinkInventory(@NotNull InventorySequence sequence) {
        Objects.requireNonNull(sequence);
        synchronized (this) {
            if (!this.declaredSequences.contains(sequence)) {
                return false;
            }
            this.declaredSequences = withRemoved(this.declaredSequences, sequence);
            this.participatingSequences = withRemoved(this.participatingSequences, sequence);
            return true;
        }
    }

    @Override
    @NotNull
    public final Set<InventorySequence> linkedSequences() {
        return this.declaredSequences;
    }

    @Override
    @NotNull
    public final Set<InventorySequence> participatingSequences() {
        return this.participatingSequences;
    }

    // 第一次逐个声明 Inventory 时创建内部序列并加入参与集
    @NotNull
    private InventorySequence ownSequence() {
        InventorySequence current = this.ownSequence;
        if (current == null) {
            synchronized (this) {
                current = this.ownSequence;
                if (current == null) {
                    current = InventorySequence.of();
                    this.ownSequence = current;
                    this.participatingSequences = withAdded(this.participatingSequences, current);
                }
            }
        }
        return current;
    }

    @NotNull
    private static Set<InventorySequence> withAdded(Set<InventorySequence> current, InventorySequence sequence) {
        LinkedHashSet<InventorySequence> updated = new LinkedHashSet<>(current);
        updated.add(sequence);
        return Collections.unmodifiableSet(updated);
    }

    @NotNull
    private static Set<InventorySequence> withRemoved(Set<InventorySequence> current, InventorySequence sequence) {
        LinkedHashSet<InventorySequence> updated = new LinkedHashSet<>(current);
        updated.remove(sequence);
        return Collections.unmodifiableSet(updated);
    }

    @NotNull
    @Override
    public final synchronized PaneSlotAttachment attach(int slot, @NotNull Observer<? super Pane> observer) {
        Objects.requireNonNull(observer, "observer");
        SlotObserver head = this.observers[slot];
        SlotObserver subscription = new SlotObserver(this, slot, observer, head);
        if (head != null) {
            head.previous = subscription;
        }
        this.observers[slot] = subscription;
        return new PaneSlotAttachment(this.elements[slot], this.frozen, subscription);
    }

    @Override
    @NotNull
    public final Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super Pane> callback) {
        Objects.requireNonNull(callback, "callback");
        return this.bindings.bind(() -> signal.onDirty(() -> callback.accept(this)));
    }

    // 摘链后清空 Pane 与观察者引用
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

    // 复制活订阅, 供锁外回调
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

    // 某个观察者失败也会继续通知快照中的其余观察者
    private RuntimeException notify(SlotObserver[] observers, RuntimeException failure) {
        if (observers == null) {
            return failure;
        }
        for (SlotObserver current : observers) {
            Observer<? super Pane> observer = current.observer;
            if (observer == null) {
                continue;
            }
            try {
                observer.onUpdate(this);
            } catch (RuntimeException exception) {
                failure = ThrowableUtils.combine(failure, exception);
            }
        }
        return failure;
    }

    private static final class SlotObserver implements Subscription {
        private volatile AbstractPane owner;
        private final int slot;
        private volatile Observer<? super Pane> observer;

        private SlotObserver previous;
        private volatile SlotObserver next;
        private volatile boolean active = true;

        private SlotObserver(
                AbstractPane owner,
                int slot,
                Observer<? super Pane> observer,
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
            AbstractPane owner = this.owner;
            if (owner != null) {
                owner.remove(this);
            }
        }
    }
}
