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
    private final Structure structure;                // 槽位布局
    private final Element[] elements;                 // 每个槽位现在放的元素, 只在 this 锁里改
    private final SlotObserver[] observers;           // 每个槽位一条订阅链, 数组的一格就是链头
    private final Bindings bindings = new Bindings(); // 这个 Pane 持有的 Signal 绑定, 回收时统一摘
    private final PaneVisualImpl visual;              // 视觉配置, 背景和逐槽的显示路径都挂在它上面

    private boolean frozen;                                                    // 冻住之后玩家点击不会走到这个 Pane 上, 显示和刷新照常
    @Nullable private volatile InventorySequence ownSequence;                  // 逐个声明来的 Inventory 攒成的内部序列, 没人声明过就是 null
    private volatile Set<InventorySequence> declaredSequences = Set.of();      // 整条声明进来的序列, 可以再摘掉
    private volatile Set<InventorySequence> participatingSequences = Set.of(); // 上面两者的并集, 保持声明顺序, 只在声明变化时重造

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
            // 还是原来那个实例就当没变, 不叫订阅者
            Element previous = this.elements[slot];
            if (previous == element) {
                return;
            }
            this.elements[slot] = element;
            observers = this.snapshot(this.observers[slot]);
        }
        // 回调在锁外跑, 里面再动这个 Pane 也不会死锁
        this.publish(observers);
    }

    // 元素全部生成成功才进短锁写进数组, 生成过程中抛异常的话 Pane 一个字都不动.
    @Override
    public final void setElements(
            @NotNull SlotSequence slots,
            @NotNull ElementSupplier supplier,
            boolean replaceExisting
    ) {
        if (!this.size().equals(slots.paneSize())) {
            throw new IllegalArgumentException("slot sequence belongs to " + slots.paneSize() + ", expected " + this.size());
        }

        // 生成放在锁外, 这里跑的是调用方给的 supplier
        int length = slots.length();
        Element[] replacements = new Element[length];
        for (int occurrence = 0; occurrence < length; occurrence++) {
            Element replacement = supplier.get(slots, occurrence);
            replacements[occurrence] = replacement;
        }

        SlotObserver[][] changedObservers = new SlotObserver[length][];
        int[] indices = slots.unsafeSlots();
        // 一次短锁里写完, 顺手取出要通知的订阅快照, 出锁再叫它们
        synchronized (this) {
            for (int occurrence = 0; occurrence < length; occurrence++) {
                int slot = indices[occurrence];
                Element previous = this.elements[slot];
                // replaceExisting 为假时只填空槽, 已经有内容的槽位整格跳过
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
        // 整批先查一遍 null, 有一个不合格就整批不生效
        for (Element element : newElements) {
            if (element == null) {
                throw new NullPointerException("elements must not contain null");
            }
        }

        this.addElementsTrusted(newElements);
    }

    @Override
    public final void addItems(Item @NotNull ... items) {
        // 包成槽位元素之后走下面同一个入口, 省得养两套逻辑
        Element[] elements = new Element[items.length];
        for (int index = 0; index < items.length; index++) {
            elements[index] = new Element.Item(items[index]);
        }
        this.addElementsTrusted(elements);
    }

    // 元素已经查过, 这里只负责把它们塞进最靠前的空槽位.
    private void addElementsTrusted(Element[] newElements) {
        SlotObserver[][] changedObservers = new SlotObserver[Math.min(newElements.length, this.elements.length)][];
        int changed = 0;
        synchronized (this) {
            int searchFrom = 0;
            for (Element element : newElements) {
                if (element == Element.Empty.INSTANCE) {
                    continue;
                }
                // 游标记住上次填到哪儿, 不用每回都从头找空位
                while (searchFrom < this.elements.length
                        && this.elements[searchFrom] != Element.Empty.INSTANCE) {
                    searchFrom++;
                }
                // 到头了, 剩下这些元素没地方放, 直接结束
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
        // 元素没动, 只是取一份订阅快照再叫一遍
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
            // 状态没变就不通知, 免得白叫一轮
            if (this.frozen == frozen) {
                return;
            }
            this.frozen = frozen;
            // 冻不冻影响每一格的交互, 所以全表的订阅都要叫醒
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
            // 自己攒的那条内部序列归逐个声明的入口管, 这里不收; 已经声明过的也不重复加
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

    // 头一次逐个声明 Inventory 时才把内部序列建出来, 顺手加进参与集; 读路径不加锁, 走双检.
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

    // 参与集对外只读, 所以每次都是复制一份新的再换上去
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

    // 新订阅挂到链头, 句柄里带上挂载那一刻的元素和冻结状态
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

    // 绑定记在 bindings 上, Pane 被回收时统一摘掉, 使用方不用自己记着退订
    @Override
    @NotNull
    public final Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super Pane> callback) {
        Objects.requireNonNull(callback, "callback");
        return this.bindings.bind(() -> signal.onDirty(() -> callback.accept(this)));
    }

    // 从链上把这个订阅摘掉, 顺手清掉两边的引用, 别让已经退订的观察者还挂在链上
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

    // 把链上还活着的订阅复制出来供锁外派发, 一个都没有就给 null
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

    // 派发通知. 用户回调抛的异常攒到最后一起抛, 前面的观察者照常收到.
    private void publish(SlotObserver[] observers) {
        RuntimeException failure = this.notify(observers, null);
        if (failure != null) {
            throw failure;
        }
    }

    private void publish(SlotObserver[][] observers) {
        this.publish(observers, observers.length);
    }

    // 只派发前面 length 项, 数组尾部是没派上用场的空位
    private void publish(SlotObserver[][] observers, int length) {
        RuntimeException failure = null;
        for (int index = 0; index < length; index++) {
            failure = this.notify(observers[index], failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    // 一个观察者抛异常也要把剩下的叫完, 异常攒着最后一起抛
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

    // 槽位订阅链上的一个节点, 同时也是交给使用方的 Subscription 句柄; 改链的操作都在 Pane 的锁里
    private static final class SlotObserver implements Subscription {
        private volatile AbstractPane owner;   // 摘掉之后清成 null
        private final int slot;
        private volatile Observer<? super Pane> observer; // 摘掉之后清成 null

        // next 会被无锁遍历读到, 所以是 volatile; previous 只在 Pane 锁里碰, 不用
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
