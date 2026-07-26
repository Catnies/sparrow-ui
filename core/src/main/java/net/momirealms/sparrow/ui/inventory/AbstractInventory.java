package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 库存实现的并发骨架: 不可变快照 + 短写锁.
 * <p>槽状态收敛为一个 volatile 引用指向的数组快照; 数组创建后永不修改, 替换状态
 * 就是换引用, 这次引用交换即事务的线性化点. 读路径直接取当前快照, 完全无锁;
 * 写路径由 {@link InventoryTransactions} 在写锁内完成校验与交换, 用户回调一律
 * 在锁外执行.
 */
abstract class AbstractInventory implements Inventory {
    private static final AtomicLong LOCK_ORDER_SOURCE = new AtomicLong(); // 进程内锁序号发号器

    private final long lockOrder = LOCK_ORDER_SOURCE.getAndIncrement(); // 跨库存事务的全序加锁依据
    private final ReentrantLock writeLock = new ReentrantLock(); // 仅串行化写者, 临界区为纯内存操作

    private final ObservableDispatcher<TransactionPreEvent> preUpdates = new ObservableDispatcher<>();
    private final ObservableDispatcher<TransactionPostEvent> postUpdates = new ObservableDispatcher<>();
    private final ConcurrentLinkedQueue<TransactionPostEvent> pendingPostEvents = new ConcurrentLinkedQueue<>(); // 锁内入队保证顺序 = 提交顺序
    private final AtomicBoolean drainingPostEvents = new AtomicBoolean(); // 排水者标志, 同一时刻至多一个线程派发

    private volatile @Nullable ItemStack @NotNull [] state; // 当前不可变快照, 元素归内部所有

    AbstractInventory(@Nullable ItemStack @NotNull [] initial) {
        // 构造即快照化: 归一化并逐元素克隆, 此后修改原数组或原物品不影响库存
        @Nullable ItemStack[] slots = new ItemStack[initial.length];
        for (int i = 0; i < initial.length; i++) {
            slots[i] = ItemStackValues.cloneOrNull(ItemStackValues.normalize(initial[i]));
        }
        this.state = slots;
    }

    @Override
    public int size() {
        return this.state.length;
    }

    @Override
    @Nullable
    public ItemStack itemAt(int slot) {
        @Nullable ItemStack[] snapshot = this.state;
        return ItemStackValues.cloneOrNull(snapshot[slot]);
    }

    // 单次 volatile 读取即得到一致性视图, 逐元素克隆后交给调用方
    @Override
    public @Nullable ItemStack @NotNull [] snapshot() {
        @Nullable ItemStack[] copy = new ItemStack[this.state.length];
        for (int i = 0; i < this.state.length; i++) {
            copy[i] = ItemStackValues.cloneOrNull(this.state[i]);
        }
        return copy;
    }

    @Override
    @NotNull
    public Subscription subscribePreUpdate(@NotNull Observer<? super TransactionPreEvent> observer) {
        return this.preUpdates.subscribe(observer);
    }

    @Override
    @NotNull
    public Subscription subscribePostUpdate(@NotNull Observer<? super TransactionPostEvent> observer) {
        return this.postUpdates.subscribe(observer);
    }

    long lockOrder() {
        return this.lockOrder;
    }

    @NotNull
    ReentrantLock writeLock() {
        return this.writeLock;
    }

    // 返回当前快照引用本身: plan 以它为基准, commit 以 identity 比对完成乐观校验
    @Nullable
    ItemStack @NotNull [] currentState() {
        return this.state;
    }

    // 线性化点: 仅允许在持有写锁时调用
    void swapState(@Nullable ItemStack @NotNull [] newState) {
        this.state = newState;
    }

    /**
     * 在锁外向本库存的 pre 观察者派发事件, 运行时异常隔离上报, 不中止事务.
     */
    void publishPreUpdate(@NotNull TransactionPreEvent event) {
        try {
            this.preUpdates.publish(event);
        } catch (RuntimeException exception) {
            SparrowUI.getInstance().handleException("Failed to handle Inventory pre-update", exception);
        }
    }

    // 仅允许在持有写锁时调用, 使队列顺序与快照交换顺序一致
    void enqueuePostEvent(@NotNull TransactionPostEvent event) {
        this.pendingPostEvents.add(event);
    }

    /**
     * 以"提交者线程排水"的方式按提交顺序派发 post 事件.
     * <p>提交者在放锁后调用: 抢到排水者标志的线程负责把队列按序发完, 其余提交者
     * 入队即返回, 因此派发不阻塞后续提交, 且同一库存的事件顺序始终等于提交顺序.
     */
    void drainPostEvents() {
        while (this.drainingPostEvents.compareAndSet(false, true)) {
            try {
                // 独占排水: 逐个出队派发, 单个观察者的异常隔离上报, 不中断排水
                TransactionPostEvent event;
                while ((event = this.pendingPostEvents.poll()) != null) {
                    try {
                        this.postUpdates.publish(event);
                    } catch (RuntimeException exception) {
                        SparrowUI.getInstance().handleException("Failed to handle Inventory post-update", exception);
                    }
                }
            } finally {
                this.drainingPostEvents.set(false);
            }

            // 释放标志后复查: 若有事件恰在退出间隙入队且对方未能抢到标志, 由本线程重新排水
            if (this.pendingPostEvents.isEmpty()) {
                break;
            }
        }
    }
}
