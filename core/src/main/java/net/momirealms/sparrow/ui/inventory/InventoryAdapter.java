package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;

/**
 * 把 Sparrow Inventory 包装成 Bukkit 的 Inventory 接口.
 * <p>所有写操作都会转成事务提交(原因记为 {@link UpdateReason.Program}), 线程约定与被包装的
 * Inventory 一致: 快照型Inventory在任何线程都能写; 引用型Inventory当前线程够不到目标容器时, 没有返回值的
 * 写操作会被静默忽略, add/remove 则用 leftovers 告诉调用方"这些没放进去".
 * 读操作都基于当前快照的克隆, 改动返回值不会影响库存.
 * <p>它不是真实容器, 不持有观看者, 持有者, 位置, 类型等属性.
 */
final class InventoryAdapter implements org.bukkit.inventory.Inventory {
    private final Inventory inventory; // 被包装的 Sparrow 库存

    /**
     * 创建包装实例. 一般由 {@code asBukkitInventory()} 懒创建并缓存, 保证同一库存恒为同一实例.
     *
     * @param inventory 被包装的库存
     */
    InventoryAdapter(Inventory inventory) {
        this.inventory = inventory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSize() {
        return this.inventory.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现取所有槽位上限中的最大值;
     * 零尺寸Inventory回退默认值, 避免向 Bukkit 调用方报告 0 上限.
     */
    @Override
    public int getMaxStackSize() {
        int max = 0;
        for (int slot = 0; slot < this.inventory.size(); slot++) {
            max = Math.max(max, this.inventory.slotMaxStackSize(slot));
        }
        // 零尺寸Inventory回退默认值.
        return max > 0 ? max : Inventory.DEFAULT_MAX_STACK_SIZE;
    }

    /**
     * 把全部槽位的堆叠上限统一设为 size.
     * <p>只有被包装的是 {@link VirtualInventory} 时才生效;
     * 其他实现(比如ReferencingInventory)的上限由外部容器说了算, 调用本方法会被静默忽略.
     *
     * @param size 新的堆叠上限
     */
    @Override
    public void setMaxStackSize(int size) {
        if (this.inventory instanceof VirtualInventory virtualInventory) {
            int[] maxes = new int[virtualInventory.size()];
            Arrays.fill(maxes, size);
            virtualInventory.slotMaxStackSizes(maxes);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public ItemStack getItem(int index) {
        return this.inventory.itemAt(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setItem(int index, @Nullable ItemStack item) {
        this.inventory.setItem(UpdateReason.Program.INSTANCE, index, item);
    }

    /**
     * {@inheritDoc}
     *
     * <p>入参里的 {@code null} 元素直接抛异常; 每个物品各自提交一笔事务,
     * 因此前面的物品放成功了, 后面的物品仍可能因为取消或冲突没放进去.
     */
    @Override
    @NotNull
    public HashMap<Integer, ItemStack> addItem(@Nullable ItemStack @NotNull ... items) {
        HashMap<Integer, ItemStack> leftovers = new HashMap<>();
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null) {
                throw new IllegalArgumentException("Item at index " + i + " is null");
            }
            AddResult result = this.inventory.add(UpdateReason.Program.INSTANCE, item);
            if (result.remaining() > 0) {
                leftovers.put(i, ItemUtils.copyWithAmount(item, result.remaining()));
            }
        }
        return leftovers;
    }

    /**
     * {@inheritDoc}
     *
     * <p>入参里的 {@code null} 元素直接抛异常; 每个物品各自提交一笔事务,
     * 移除按"与给定物品完全相似"匹配.
     */
    @Override
    @NotNull
    public HashMap<Integer, ItemStack> removeItem(@Nullable ItemStack @NotNull ... items) {
        HashMap<Integer, ItemStack> leftovers = new HashMap<>();
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null) {
                throw new IllegalArgumentException("Item at index " + i + " is null");
            }
            RemoveResult result = this.inventory.remove(UpdateReason.Program.INSTANCE, stack -> stack.isSimilar(item), item.getAmount());
            int leftOver = item.getAmount() - result.removed();
            if (leftOver > 0) {
                leftovers.put(i, ItemUtils.copyWithAmount(item, leftOver));
            }
        }
        return leftovers;
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现与 {@link #removeItem} 相同.
     */
    @Override
    @NotNull
    public HashMap<Integer, ItemStack> removeItemAnySlot(@Nullable ItemStack @NotNull ... items) {
        return this.removeItem(items);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable ItemStack @NotNull [] getContents() {
        return this.inventory.snapshot();
    }

    /**
     * {@inheritDoc}
     *
     * <p>逐槽覆盖写入, 每个槽各自一笔事务, 不是一次整体原子替换;
     * 空槽写空属于无变化, 会被跳过.
     */
    @Override
    public void setContents(@Nullable ItemStack @NotNull [] items) {
        if (items.length > this.getSize()) {
            throw new IllegalArgumentException("Array size (" + items.length + ") exceeds inventory size (" + this.getSize() + ")");
        }
        @Nullable ItemStack[] current = this.inventory.snapshot();
        for (int slot = 0; slot < this.getSize(); slot++) {
            @Nullable ItemStack target = slot < items.length ? items[slot] : null;
            // 空槽写空是无变更操作, 跳过以免派发幻影权威写事件
            if (target == null && current[slot] == null) {
                continue;
            }
            this.inventory.setItem(UpdateReason.Program.INSTANCE, slot, target);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现与 {@link #getContents} 相同.
     */
    @Override
    public @Nullable ItemStack @NotNull [] getStorageContents() {
        return this.getContents();
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现与 {@link #setContents} 相同.
     */
    @Override
    public void setStorageContents(@Nullable ItemStack @NotNull [] items) {
        this.setContents(items);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(@NotNull Material material) {
        return this.first(material) != -1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现中 {@code null} 物品固定返回 {@code false}.
     */
    @Override
    public boolean contains(@Nullable ItemStack item) {
        if (item == null) {
            return false;
        }
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (item.equals(snapshot[slot])) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(@NotNull Material material, int amount) {
        int total = 0;
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            ItemStack stack = snapshot[slot];
            if (stack != null && stack.getType() == material && (total += stack.getAmount()) >= amount) {
                return true;
            }
        }
        return amount <= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现中 {@code null} 物品固定返回 {@code false}.
     */
    @Override
    public boolean contains(@Nullable ItemStack item, int amount) {
        if (item == null) {
            return false;
        }
        int found = 0;
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (item.equals(snapshot[slot]) && ++found >= amount) {
                return true;
            }
        }
        return amount <= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现中 {@code null} 物品固定返回 {@code false}.
     */
    @Override
    public boolean containsAtLeast(@Nullable ItemStack item, int amount) {
        if (item == null) {
            return false;
        }
        int total = 0;
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            ItemStack stack = snapshot[slot];
            if (stack != null && stack.isSimilar(item) && (total += stack.getAmount()) >= amount) {
                return true;
            }
        }
        return amount <= 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public HashMap<Integer, ? extends ItemStack> all(@NotNull Material material) {
        HashMap<Integer, ItemStack> found = new HashMap<>();
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            ItemStack stack = snapshot[slot];
            if (stack != null && stack.getType() == material) {
                found.put(slot, stack);
            }
        }
        return found;
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现中 {@code null} 物品返回空表.
     */
    @Override
    @NotNull
    public HashMap<Integer, ? extends ItemStack> all(@Nullable ItemStack item) {
        HashMap<Integer, ItemStack> found = new HashMap<>();
        if (item == null) {
            return found;
        }
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (item.equals(snapshot[slot])) {
                found.put(slot, snapshot[slot]);
            }
        }
        return found;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int first(@NotNull Material material) {
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (snapshot[slot] != null && snapshot[slot].getType() == material) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int first(@NotNull ItemStack item) {
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (item.equals(snapshot[slot])) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int firstEmpty() {
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (snapshot[slot] == null) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        @Nullable ItemStack[] snapshot = this.inventory.snapshot();
        for (int slot = 0; slot < snapshot.length; slot++) {
            if (snapshot[slot] != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove(@NotNull Material material) {
        this.inventory.remove(UpdateReason.Program.INSTANCE, stack -> stack.getType() == material, Integer.MAX_VALUE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove(@NotNull ItemStack item) {
        this.inventory.remove(UpdateReason.Program.INSTANCE, item::equals, Integer.MAX_VALUE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear(int index) {
        this.setItem(index, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>只清非空槽, 已经空着的槽不会收到一个没有实际效果的写事件.
     */
    @Override
    public void clear() {
        // 只清非空槽, 避免为已空槽派发幻影权威写事件
        @Nullable ItemStack[] current = this.inventory.snapshot();
        for (int slot = 0; slot < current.length; slot++) {
            if (current[slot] != null) {
                this.setItem(slot, null);
            }
        }
    }

    /**
     * 本包装不对应任何打开的窗口, 没有观看者可以关闭, 固定返回 0.
     *
     * @return 固定为 0
     */
    @Override
    public int close() {
        return 0;
    }

    /**
     * 本包装不是真实容器, 不存在"正在看它的人", 固定返回空列表.
     *
     * @return 固定为空列表
     */
    @Override
    @NotNull
    public List<HumanEntity> getViewers() {
        return List.of();
    }

    /**
     * 被包装的库存没有自己的容器类型概念, 固定回答 {@link InventoryType#CHEST}.
     *
     * @return 固定为 {@link InventoryType#CHEST}
     */
    @Override
    @NotNull
    public InventoryType getType() {
        return InventoryType.CHEST;
    }

    /**
     * 本包装不挂在任何方块或实体上, 没有持有者, 固定返回 {@code null}.
     *
     * @return 固定为 {@code null}
     */
    @Override
    @Nullable
    public InventoryHolder getHolder() {
        return null;
    }

    /**
     * 与 {@link #getHolder()} 相同, 固定返回 {@code null}.
     *
     * @param useSnapshot 是否使用快照, 对本实现没有影响
     * @return 固定为 {@code null}
     */
    @Override
    @Nullable
    public InventoryHolder getHolder(boolean useSnapshot) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public ListIterator<ItemStack> iterator() {
        return this.iterator(0);
    }

    /**
     * {@inheritDoc}
     *
     * <p>迭代的是当前快照的只读视图: {@code set} 等修改操作会抛异常, 负下标按 CraftBukkit 的习惯从尾部回绕.
     */
    @Override
    @NotNull
    public ListIterator<ItemStack> iterator(int index) {
        // CraftBukkit 兼容: 负下标从尾部回绕
        if (index < 0) {
            index += this.getSize();
        }
        // 快照的只读迭代: set 等修改操作抛出异常, 而不是静默写进脱离库存的副本
        return Collections.unmodifiableList(Arrays.asList(this.inventory.snapshot())).listIterator(index);
    }

    /**
     * 本包装不对应世界中的任何位置, 固定返回 {@code null}.
     *
     * @return 固定为 {@code null}
     */
    @Override
    @Nullable
    public Location getLocation() {
        return null;
    }
}
