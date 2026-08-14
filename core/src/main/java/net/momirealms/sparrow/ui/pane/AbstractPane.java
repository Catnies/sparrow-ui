package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.InventorySequence;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 保存 Pane 的槽位元素, 背景, 冻结状态和逐槽订阅.
 *
 * <p>所有状态读写都在 Pane 锁内完成, 订阅回调统一在锁外发布,
 * 避免观察者回调用户代码时发生死锁.
 */
abstract non-sealed class AbstractPane implements Pane {
    private final Structure structure;      // 槽位布局
    private final Element[] elements;   // 每个槽位当前保存的元素
    private final SlotObserver[] observers; // 每个槽位对应一条订阅链的头节点
    private final SignalBindings signalBindings = new SignalBindings(); // 持有的 Signal 绑定

    private ItemProvider background;    // 空槽位显示的背景, 可为 null
    private boolean frozen;             // 是否禁止玩家交互
    // 额外参与的 Inventory 序列, 写时整体替换为新的不可变快照, 读不加锁
    private volatile Set<InventorySequence> linkedSequences = Set.of();
    @Nullable private volatile InventorySequence ownSequence; // 逐个声明的 Inventory 都放在这条序列里, 它同样登记在 linkedSequences

    AbstractPane(Structure structure, Element[] elements, ItemProvider background, boolean frozen) {
        this.structure = structure;
        this.elements = elements;
        this.background = background;
        this.frozen = frozen;
        this.observers = new SlotObserver[elements.length];
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
            // 元素未变化时不触发任何通知
            Element previous = this.elements[slot];
            if (previous == element) {
                return;
            }
            this.elements[slot] = element;
            // 在锁内取出订阅快照, 保证通知与本次变更对应
            observers = this.snapshot(this.observers[slot]);
        }
        // 回调在锁外发布, 避免观察者回调用户代码时死锁
        this.publish(observers);
    }

    // 在锁外完成元素生成, 再在一次短锁中应用变更并发布订阅快照.
    @Override
    public final void setElements(
            @NotNull SlotSequence slots,
            @NotNull ElementSupplier supplier,
            boolean replaceExisting
    ) {
        if (!this.size().equals(slots.paneSize())) {
            throw new IllegalArgumentException("slot sequence belongs to " + slots.paneSize() + ", expected " + this.size());
        }

        // 先在锁外生成全部元素, 任何失败都不会留下半批修改
        int length = slots.length();
        Element[] replacements = new Element[length];
        for (int occurrence = 0; occurrence < length; occurrence++) {
            Element replacement = supplier.get(slots, occurrence);
            replacements[occurrence] = replacement;
        }

        SlotObserver[][] changedObservers = new SlotObserver[length][];
        int[] indices = slots.unsafeSlots();
        // 在同一次短锁中写入元素, 并保存需要通知的订阅快照
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
        // 回调可能执行用户代码, 因此必须在 Pane 锁外发布
        this.publish(changedObservers);
    }

    @Override
    public final void addElements(Element @NotNull ... newElements) {
        // 预先拒绝 null 元素, 避免写入一半才失败
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
    @Nullable
    public final synchronized ItemProvider background() {
        return this.background;
    }

    @Override
    public final void setBackground(@Nullable ItemProvider background) {
        SlotObserver[][] observers;
        synchronized (this) {
            // 背景未变化时不触发任何通知
            if (this.background == background) {
                return;
            }
            this.background = background;
            // 背景影响所有空槽位的显示, 需要通知全部槽位的订阅
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
        InventorySequence own = this.ownSequence();
        own.add(inventory);
        // 这个序列可能被 unlinkInventory 摘掉过, 顺手放回去; 已经在里面时无操作
        this.linkInventory(own);
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
            if (this.linkedSequences.contains(sequence)) {
                return;
            }
            LinkedHashSet<InventorySequence> updated = new LinkedHashSet<>(this.linkedSequences);
            updated.add(sequence);
            this.linkedSequences = Collections.unmodifiableSet(updated);
        }
    }

    @Override
    public final boolean unlinkInventory(@NotNull InventorySequence sequence) {
        Objects.requireNonNull(sequence);
        synchronized (this) {
            if (!this.linkedSequences.contains(sequence)) {
                return false;
            }
            LinkedHashSet<InventorySequence> updated = new LinkedHashSet<>(this.linkedSequences);
            updated.remove(sequence);
            this.linkedSequences = Collections.unmodifiableSet(updated);
            return true;
        }
    }

    @Override
    @NotNull
    public final Set<InventorySequence> linkedSequences() {
        return this.linkedSequences;
    }

    /**
     * 返回本 Pane 自己那条序列, 逐个声明的 Inventory 都存在里面.
     * <p>第一次逐个声明时才创建. 它同样登记在 {@link #linkedSequences()} 里, 于是"声明了哪些 Inventory"
     * 与"声明了哪些序列"对外只剩一种形状, 读方不必分两路展开; 已经退役的成员也随它一起被剔除.
     *
     * @return 本 Pane 自己那条序列
     */
    @NotNull
    private InventorySequence ownSequence() {
        InventorySequence current = this.ownSequence;
        if (current == null) {
            synchronized (this) {
                current = this.ownSequence;
                if (current == null) {
                    current = InventorySequence.of();
                    this.ownSequence = current;
                }
            }
        }
        return current;
    }

    @NotNull
    @Override
    public final synchronized PaneSlotAttachment attach(int slot, @NotNull Observer<? super Pane> observer) {
        Objects.requireNonNull(observer, "observer");
        // 头插法把新订阅挂到链头
        SlotObserver head = this.observers[slot];
        SlotObserver subscription = new SlotObserver(this, slot, observer, head);
        if (head != null) {
            head.previous = subscription;
        }
        this.observers[slot] = subscription;
        // 快照当前状态, 与订阅一起交还调用方
        return new PaneSlotAttachment(
                this.elements[slot],
                this.background,
                this.frozen,
                subscription
        );
    }

    @Override
    @NotNull
    public final Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super Pane> callback) {
        Objects.requireNonNull(callback, "callback");
        return this.signalBindings.add(signal.onDirty(() -> callback.accept(this)));
    }

    // 从槽位订阅链中断开指定节点, 并清除它持有的引用.
    private synchronized void remove(SlotObserver subscription) {
        // 已断开的节点直接忽略, 保证重复 close 无副作用
        if (!subscription.active) {
            return;
        }
        subscription.active = false;

        // 标准双向链摘链
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
        // 清除引用, 让已关闭订阅持有的观察者和 Pane 可以被回收
        subscription.previous = null;
        subscription.next = null;
        subscription.observer = null;
        subscription.owner = null;
    }

    // 复制一个槽位中仍然有效的订阅, 供锁外回调使用.
    private SlotObserver[] snapshot(SlotObserver head) {
        if (head == null) {
            return null;
        }

        // 先统计有效订阅数量
        int size = 0;
        for (SlotObserver current = head; current != null; current = current.next) {
            if (current.active) {
                size++;
            }
        }
        if (size == 0) {
            return null;
        }

        // 再按链表顺序收集快照
        SlotObserver[] snapshot = new SlotObserver[size];
        int index = 0;
        for (SlotObserver current = head; current != null; current = current.next) {
            if (current.active) {
                snapshot[index++] = current;
            }
        }
        return snapshot;
    }

    // 复制所有槽位的订阅, 用于背景或冻结状态更改.
    private SlotObserver[][] snapshotAll() {
        SlotObserver[][] snapshots = new SlotObserver[this.observers.length][];
        for (int slot = 0; slot < snapshots.length; slot++) {
            snapshots[slot] = this.snapshot(this.observers[slot]);
        }
        return snapshots;
    }

    //  发布一个槽位的订阅快照, 回调失败时抛出合并后的异常.
    private void publish(SlotObserver[] observers) {
        RuntimeException failure = this.notify(observers, null);
        if (failure != null) {
            throw failure;
        }
    }

    // 发布全部槽位的订阅快照.
    private void publish(SlotObserver[][] observers) {
        this.publish(observers, observers.length);
    }

    // 发布前 length 个槽位的订阅快照.
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
     *
     * @param observers 一个槽位的订阅快照, 可为 null
     * @param failure 已收集的第一个异常, 可为 null
     * @return 合并后的第一个异常, 没有异常时返回 null
     */
    private RuntimeException notify(SlotObserver[] observers, RuntimeException failure) {
        if (observers == null) {
            return failure;
        }
        for (SlotObserver current : observers) {
            // 订阅可能已关闭并被清除了引用
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

    /**
     * 一次槽位订阅在双向链中的节点, 调用 close 可以直接断开该节点.
     */
    private static final class SlotObserver implements Subscription {
        private volatile AbstractPane owner; // 所属 Pane, 关闭后清除
        private final int slot;             // 订阅的槽位编号
        private volatile Observer<? super Pane> observer; // 更新观察者, 关闭后清除

        private SlotObserver previous;          // 更晚加入的订阅
        private volatile SlotObserver next;     // 更早加入的订阅
        private volatile boolean active = true; // 订阅是否仍在链上

        /**
         * 创建订阅节点并链接到原有链头之前.
         *
         * @param owner 所属 Pane
         * @param slot 订阅的槽位编号
         * @param observer 更新观察者
         * @param next 原链头节点, 可为 null
         */
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
            // owner 在 remove 中被清除, 重复关闭时自然跳过
            AbstractPane owner = this.owner;
            if (owner != null) {
                owner.remove(this);
            }
        }
    }
}
