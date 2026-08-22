package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.exception.InventoryDecodeException;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.inventory.transaction.InventoryUpdateChannel;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.UUID;

/**
 * 自己持有槽位数据的 Inventory 实现, 提交时加写锁并整体换掉状态数组, 因此任何线程都可以安全读取.
 * <p><strong>无锁读与并发校验都建立在"状态数组的元素一经发布就不再被改动"之上</strong>, 提交只换数组不动元素,
 * 所以比对数组引用就足以发现期间有没有别人写过.
 */
public final class VirtualInventory extends SparrowInventory {
    private final UUID uuid; // 持久化身份, 序列化时作为主键
    private volatile int @Nullable [] slotMaxStackSizes;    // 逐槽堆叠上限, null 表示全部用默认值; 数组不可变, 改就整组替换
    private volatile @Nullable SlotOrder addOrder;          // ADD 类别的遍历顺序, null 回退自然顺序
    private volatile @Nullable SlotOrder collectOrder;
    private volatile @Nullable SlotOrder otherOrder;

    /**
     * 以随机 UUID 创建全空 Inventory.
     *
     * @param size 槽位数量
     */
    public VirtualInventory(int size) {
        this(UUID.randomUUID(), size);
    }

    /**
     * 以给定 UUID 创建全空 Inventory.
     *
     * @param uuid 持久化身份
     * @param size 槽位数量
     * @throws IllegalArgumentException 当尺寸为负数时
     */
    public VirtualInventory(@NotNull UUID uuid, int size) {
        super(new ItemStack[requireNonNegativeSize(size)]);
        this.uuid = uuid;
    }

    /**
     * 以随机 UUID 创建 Inventory, 并复制给定的初始槽内容.
     *
     * @param initial 初始槽位内容, 空槽位置为 {@code null}
     */
    public VirtualInventory(@Nullable ItemStack @NotNull [] initial) {
        this(UUID.randomUUID(), initial);
    }

    /**
     * 以给定 UUID 创建 Inventory, 并复制给定的初始槽内容.
     *
     * @param uuid 持久化身份
     * @param initial 初始槽位内容, 空槽位置为 {@code null}
     */
    public VirtualInventory(@NotNull UUID uuid, @Nullable ItemStack @NotNull [] initial) {
        super(initial);
        this.uuid = uuid;
    }

    /**
     * 从字节数组反序列化出 Inventory.
     * <p>只恢复 UUID 与槽内容, 堆叠上限等配置不随数据回来;
     * 必须在 SparrowUI 完成 setup 后调用.
     *
     * @param bytes 序列化数据
     * @return 还原出的 Inventory
     * @throws InventoryDecodeException 当输入不是本实现支持的完整数据时
     */
    @NotNull
    public static VirtualInventory deserialize(byte @NotNull [] bytes) {
        return VirtualInventoryCodec.deserialize(bytes);
    }

    /**
     * 把 UUID 与当前这一刻的槽内容序列化成字节数组.
     * <p>必须在 SparrowUI 完成 setup 后调用.
     *
     * @return 序列化数据
     */
    public byte @NotNull [] serialize() {
        return VirtualInventoryCodec.serialize(this);
    }

    /**
     * 返回本 Inventory 的持久化身份.
     *
     * @return 持久化用的 UUID
     */
    @NotNull
    public UUID uuid() {
        return this.uuid;
    }

    /**
     * 返回本 Inventory 是否有序串行派发 PostUpdateEvent.
     *
     * @return 开启串行派发时返回 {@code true}
     */
    public boolean serialPostDispatch() {
        // 只读, 所以不去建通道.
        InventoryUpdateChannel channel = this.updateChannelIfPresent();
        return channel != null && channel.serialPostDispatch();
    }

    /**
     * 设置本 Inventory 是否有序串行派发 PostUpdateEvent, 默认关闭.
     * <p>此选项主要针对如 Folia 平台玩家在多个不同线程同时修改共享的 Inventory 或手动异步并发修改 Inventory 的情况.
     * 只要确保本 Inventory 不会在多个线程同时修改, 或 Post 任务可以接受多线程并发就无需开启本选项.
     * <p>关闭时: 每笔事务由自己的提交线程立即派发, 多个线程可以并发调用同一个处理器, 且到达顺序不等于提交顺序;
     * 处理器需要自己做互斥, 并按事件的 {@code version()} 判断新旧.
     * <p>开启后: 本 Inventory 的 PostUpdateEvent 严格按提交顺序逐笔派发, 因此可以直接读写外部状态.
     * 代价是后提交的线程会阻塞到前一笔的处理器全部结束.
     *
     * @param serialPostDispatch 是否串行派发
     */
    public void serialPostDispatch(boolean serialPostDispatch) {
        this.updateChannel().serialPostDispatch(serialPostDispatch);
    }

    @Override
    public int slotMaxStackSize(int slot) {
        int[] maxes = this.slotMaxStackSizes;
        if (maxes != null) {
            return maxes[slot];
        }
        return super.slotMaxStackSize(slot);
    }

    /**
     * 设置单个槽位的堆叠上限.
     *
     * @param slot 槽位序号, 从 0 开始
     * @param max 堆叠上限, 至少为 1
     * @throws IllegalArgumentException 当上限小于 1 时
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public synchronized void setMaxStackSize(int slot, int max) {
        if (max < 1)
            throw new IllegalArgumentException("max stack size must be at least 1: " + max);
        // 写时复制发布, 改一个槽也复制整组再换引用, 读者才能无锁读到完整的一份.
        // synchronized 是给写者排队用的, 复制-修改-写回不是原子的, 两个线程同时改会各自基于旧数组互相覆盖.
        int[] current = this.slotMaxStackSizes;
        int[] next = current != null ? current.clone() : filledWithDefault(this.size());
        next[slot] = max;
        this.slotMaxStackSizes = next;
    }

    /**
     * 整组设置每个槽位的堆叠上限.
     *
     * @param maxes 每个槽位的堆叠上限, 长度必须等于槽位数量
     * @throws IllegalArgumentException 当数组长度与槽位数量不符或任一上限小于 1 时
     */
    public synchronized void setMaxStackSizes(int @NotNull [] maxes) {
        if (maxes.length != this.size()) {
            throw new IllegalArgumentException("max stack size array length " + maxes.length + " does not match inventory size " + this.size());
        }
        int[] copy = maxes.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 1)
                throw new IllegalArgumentException("max stack size must be at least 1: " + copy[i]);
        }
        this.slotMaxStackSizes = copy;
    }

    /**
     * 设置指定类别的批量操作按什么顺序遍历槽位.
     *
     * @param category 操作类别
     * @param order 遍历顺序, 尺寸必须等于槽位数量
     * @throws IllegalArgumentException 当顺序尺寸与槽位数量不符时
     */
    public void setIterationOrder(@NotNull OperationCategory category, @NotNull SlotOrder order) {
        if (order.size() != this.size()) {
            throw new IllegalArgumentException("iteration order size " + order.size() + " does not match inventory size " + this.size());
        }
        switch (category) {
            case ADD -> this.addOrder = order;
            case COLLECT -> this.collectOrder = order;
            case OTHER -> this.otherOrder = order;
        }
    }

    /**
     * 返回指定类别的批量操作按什么顺序遍历槽位, 设置过自定义顺序的用自定义顺序, 否则回退自然顺序.
     *
     * @param category 操作类别
     * @return 遍历顺序
     */
    @Override
    @NotNull
    public SlotOrder iterationOrder(@NotNull OperationCategory category) {
        SlotOrder order = switch (category) {
            case ADD -> this.addOrder;
            case COLLECT -> this.collectOrder;
            case OTHER -> this.otherOrder;
        };
        return order != null ? order : super.iterationOrder(category);
    }

    /**
     * 反转指定类别的批量操作当前生效的遍历顺序.
     * <p>反转基于当前顺序而非自然顺序: 之前设置过的自定义顺序同样被反转, 连续反转两次回到原顺序.
     *
     * @param category 操作类别
     */
    public synchronized void reverseIterationOrder(@NotNull OperationCategory category) {
        this.setIterationOrder(category, this.iterationOrder(category).reversed());
    }

    /**
     * 反转全部三个类别的批量操作当前生效的遍历顺序.
     */
    public synchronized void reverseIterationOrder() {
        for (OperationCategory category : OperationCategory.values()) this.reverseIterationOrder(category);
    }

    // 校验尺寸不为负数
    private static int requireNonNegativeSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("inventory size must not be negative: " + size);
        }
        return size;
    }

    // 创建填满默认堆叠上限的数组
    private static int[] filledWithDefault(int size) {
        int[] maxes = new int[size];
        Arrays.fill(maxes, DEFAULT_MAX_STACK_SIZE);
        return maxes;
    }
}
