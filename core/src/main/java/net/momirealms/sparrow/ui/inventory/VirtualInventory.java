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
 * 自己保存槽位状态的 Inventory, 提交时在写锁内整组替换状态数组.
 * <p><strong>发布后的状态数组及其元素不得修改</strong>, 无锁读取和乐观校验依赖这一约定.
 */
public final class VirtualInventory extends SparrowInventory {
    private final UUID uuid; // 持久化身份, 序列化时作为主键
    private volatile int @Nullable [] slotMaxStackSizes; // null 表示全部使用默认上限, 写入时整组替换
    private volatile @Nullable SlotOrder addOrder;
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
     * <p>只恢复 UUID 与槽内容, 堆叠上限等配置不随数据回来.
     * <strong>必须在 SparrowUI 完成 setup 后调用</strong>.
     * <p><strong>解码器不限制解压体积与 NBT 堆用量, 不可信输入应由调用方预先限制</strong>.
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
     * <p><strong>必须在 SparrowUI 完成 setup 后调用</strong>.
     *
     * @return 序列化数据
     */
    public byte @NotNull [] serialize() {
        return VirtualInventoryCodec.serialize(this);
    }

    @NotNull
    public UUID uuid() {
        return this.uuid;
    }

    public boolean serialPostDispatch() {
        // 查询配置时不创建空的更新通道.
        InventoryUpdateChannel channel = this.updateChannelIfPresent();
        return channel != null && channel.serialPostDispatch();
    }

    /**
     * 设置本 Inventory 是否有序串行派发 PostUpdateEvent, 默认关闭.
     * <p>关闭时, 多个提交线程可以并发调用同一处理器, 到达顺序也可能不同于提交顺序.
     * <strong>处理器必须自行保证线程安全, 并通过事件版本判断新旧</strong>.
     * 开启后严格按提交顺序派发, 后续提交线程会等到前一笔处理完毕.
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
     * @param max 堆叠上限, <strong>至少为 1</strong>
     * @throws IllegalArgumentException 当上限小于 1 时
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    public synchronized void setMaxStackSize(int slot, int max) {
        if (max < 1)
            throw new IllegalArgumentException("max stack size must be at least 1: " + max);
        // 写者串行完成复制与发布, 读者始终看到完整数组.
        int[] current = this.slotMaxStackSizes;
        int[] next = current != null ? current.clone() : filledWithDefault(this.size());
        next[slot] = max;
        this.slotMaxStackSizes = next;
    }

    /**
     * 整组设置每个槽位的堆叠上限.
     *
     * @param maxes 每个槽位的堆叠上限, <strong>长度必须等于槽位数量</strong>
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
     * @param order 遍历顺序, <strong>尺寸必须等于槽位数量</strong>
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

    private static int requireNonNegativeSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("inventory size must not be negative: " + size);
        }
        return size;
    }

    private static int[] filledWithDefault(int size) {
        int[] maxes = new int[size];
        Arrays.fill(maxes, DEFAULT_MAX_STACK_SIZE);
        return maxes;
    }
}
